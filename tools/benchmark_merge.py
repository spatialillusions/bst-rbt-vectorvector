"""Benchmark SQLite merges with real payloads at disjoint synthetic keys.

Input is read-only; all artifacts stay under --out. Timings include final fsync.
Uses Python SQLite to compare SQL strategies; excludes JVM overhead.
"""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import random
import sqlite3
import struct
import time


def checksum(path):
    h = hashlib.sha256()
    with closing(sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)) as c:
        assert c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        count = 0
        for z, x, y, data in c.execute("SELECT * FROM tiles ORDER BY zoom_level,tile_column,tile_row"):
            h.update(struct.pack("<IIII", z, x, y, len(data)))
            h.update(data)
            count += 1
    return count, h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("source", type=Path)
    ap.add_argument("--out", type=Path, default=Path("out/merge-benchmark"))
    ap.add_argument("--shards", type=int, default=32)
    ap.add_argument("--repeats", type=int, default=2)
    ap.add_argument("--reuse-sources", action="store_true")
    ap.add_argument("--prepare-only", action="store_true", help="Create sources for tools/MergeBenchmark.java without running Python merges")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=a.reuse_sources)
    schema = "CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)"
    index = "CREATE UNIQUE INDEX tile_index ON tiles(zoom_level,tile_column,tile_row)"
    sources = []
    with closing(sqlite3.connect(f"file:{a.source.resolve().as_posix()}?mode=ro", uri=True)) as src:
        blobs = [row[0] for row in src.execute("SELECT tile_data FROM tiles ORDER BY zoom_level,tile_column,tile_row")]
    for i in range(a.shards):
        path = a.out / f"source-{i}.mbtiles"
        if a.reuse_sources and path.is_file():
            sources.append(path)
            continue
        # Build one small synthetic seed-sized database in memory, then write its
        # serialized pages in a large buffer. Thousands of tiny file writes can
        # otherwise dominate test-data preparation on Windows USB drives.
        with sqlite3.connect(":memory:", isolation_level=None) as c:
            c.execute("PRAGMA journal_mode=OFF")
            c.execute("PRAGMA synchronous=OFF")
            c.execute(schema)
            c.execute(index)
            c.execute("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT)")
            c.execute("INSERT INTO metadata VALUES('name','synthetic merge benchmark')")
            c.execute("BEGIN")
            c.executemany("INSERT INTO tiles VALUES(13,?,?,?)", ((j % 8192, i * ((len(blobs) + 8191) // 8192) + j // 8192, b) for j, b in enumerate(blobs)))
            c.execute("COMMIT")
            serialized = c.serialize()
        c.close()
        temporary = path.with_suffix(".mbtiles.preparing")
        temporary.unlink(missing_ok=True)
        with temporary.open("xb") as destination:
            destination.write(serialized)
        temporary.replace(path)
        sources.append(path)
        if (i + 1) % 64 == 0:
            print(json.dumps({"prepared_shards": i + 1, "target_shards": a.shards,
                              "bytes": sum(p.stat().st_size for p in sources)}), flush=True)
    with a.source.open("rb") as seed:
        seed_hash = hashlib.file_digest(seed, "sha256").hexdigest()
    (a.out / "sources.json").write_text(json.dumps({"seed": str(a.source.resolve()),
        "seed_sha256": seed_hash,
        "shards": len(sources), "tiles": len(blobs) * len(sources),
        "bytes": sum(p.stat().st_size for p in sources)}, indent=2))
    if a.prepare_only:
        return
    variants = [("delete-indexed", "DELETE", "NORMAL", False),
                ("off-indexed", "OFF", "OFF", False),
                ("off-deferred", "OFF", "OFF", True)]
    jobs = [(r, v) for r in range(a.repeats) for v in variants]
    random.Random(147).shuffle(jobs)
    results, expected = [], None
    for repeat, (name, journal, sync, deferred) in jobs:
        dest = a.out / f"{name}-{repeat}.mbtiles"
        dest.unlink(missing_ok=True)
        start = time.perf_counter()
        c = sqlite3.connect(dest, isolation_level=None, uri=True)
        try:
            c.execute(f"PRAGMA journal_mode={journal}")
            c.execute(f"PRAGMA synchronous={sync}")
            c.execute("PRAGMA cache_size=-16384")
            c.execute("PRAGMA temp_store=FILE")
            c.execute(schema)
            if not deferred:
                c.execute(index)
            for source in sources:
                c.execute("ATTACH DATABASE ? AS shard", (f"file:{source.resolve().as_posix()}?mode=ro",))
                c.execute("INSERT INTO tiles SELECT * FROM shard.tiles ORDER BY zoom_level,tile_column,tile_row")
                c.execute("DETACH DATABASE shard")
            copy_seconds = time.perf_counter() - start
            if deferred:
                c.execute(index)
        finally:
            c.close()
        with dest.open("r+b") as f:
            os.fsync(f.fileno())
        elapsed = time.perf_counter() - start
        actual = checksum(dest)
        if expected is None:
            expected = actual
        assert actual == expected, (name, actual, expected)
        row = {"variant": name, "repeat": repeat, "seconds": elapsed, "copy_seconds": copy_seconds,
               "bytes": dest.stat().st_size, "tiles": actual[0], "sha256": actual[1]}
        results.append(row)
        print(json.dumps(row), flush=True)
        dest.unlink()
        (a.out / "results.json").write_text(json.dumps({"sqlite_version": sqlite3.sqlite_version,
            "source": str(a.source.resolve()), "shards": a.shards,
            "input_bytes": sum(p.stat().st_size for p in sources), "results": results}, indent=2))


if __name__ == "__main__":
    main()
