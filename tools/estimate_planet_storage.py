"""Prepare/analyze a seeded, uniform tile sample for z9–13 planet storage planning.

Generate the list with the normal RBT CLI and global OSM, then analyze its MBTiles.
Missing output rows count as zero bytes. Sampling intervals exclude uncertainty in
page-fill overhead, input quality and future builds; reserve extra planning space.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import sqlite3
import statistics


COUNTS = {9: 200, 10: 400, 11: 800, 12: 2000, 13: 8000}


def prepare(a):
    a.out.mkdir(parents=True, exist_ok=False)
    randomizer = random.Random(a.seed)
    keys = []
    for z, count in COUNTS.items():
        n = 1 << z
        keys.extend((z, key % n, key // n) for key in randomizer.sample(range(n * n), count))
    keys.sort()
    content = "".join(f"{z}/{x}/{y}\n" for z, x, y in keys)
    (a.out / "tiles.txt").write_text(content)
    (a.out / "sample.json").write_text(json.dumps({"seed": a.seed, "counts": COUNTS,
        "population_tiles": sum(4**z for z in COUNTS),
        "tile_list_sha256": hashlib.sha256(content.encode()).hexdigest()}, indent=2))
    print("prepared", len(keys), "uniformly sampled tiles", flush=True)


def analyze(a):
    manifest = json.loads((a.out / "sample.json").read_text())
    content = (a.out / "tiles.txt").read_text()
    assert hashlib.sha256(content.encode()).hexdigest() == manifest["tile_list_sha256"]
    selected = {tuple(map(int, line.split("/"))) for line in content.splitlines()}
    sizes = dict.fromkeys(selected, 0)
    with sqlite3.connect(a.candidate.resolve().as_uri() + "?mode=ro", uri=True) as database:
        assert database.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        page_size = database.execute("PRAGMA page_size").fetchone()[0]
        for z, x, row, length in database.execute("SELECT zoom_level,tile_column,tile_row,length(tile_data) FROM tiles"):
            key = z, x, (1 << z) - 1 - row
            if key not in sizes:
                raise ValueError(f"Candidate contains an unselected tile: {key}")
            sizes[key] = length
    payload = sum(sizes.values())
    nonempty = sum(value > 0 for value in sizes.values())
    if not nonempty:
        raise ValueError("No output tiles; verify the generation command")
    # Includes table/index/page-fill overhead from the actual sample database.
    fixed_archive = 5 * page_size
    overhead_per_output = max(0, a.candidate.stat().st_size - payload - fixed_archive) / nonempty
    shard_page_size = page_size
    shard_overhead = overhead_per_output
    if a.shard is not None:
        with sqlite3.connect(a.shard.resolve().as_uri() + "?mode=ro", uri=True) as source:
            assert source.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert source.execute("SELECT count(*),sum(length(tile_data)) FROM tiles").fetchone() == (nonempty, payload)
            shard_page_size = source.execute("PRAGMA page_size").fetchone()[0]
        shard_overhead = max(0, a.shard.stat().st_size - payload - 5 * shard_page_size) / nonempty
    rows = []
    total, variance, output_tiles, payload_total = 0., 0., 0., 0.
    shard_total, shard_variance, combined_variance = 0., 0., 0.
    for z, expected_count in COUNTS.items():
        values = [size for (zoom, _, _), size in sizes.items() if zoom == z]
        assert len(values) == expected_count
        population = 4**z
        storage = [value + overhead_per_output if value else 0. for value in values]
        shard_storage = [value + shard_overhead if value else 0. for value in values]
        estimated = population * statistics.mean(storage)
        var = population**2 * (1 - len(values) / population) * statistics.variance(storage) / len(values)
        factor = population**2 * (1 - len(values) / population) / len(values)
        shard_total += population * statistics.mean(shard_storage)
        shard_variance += factor * statistics.variance(shard_storage)
        combined_variance += factor * statistics.variance([x + y for x, y in zip(storage, shard_storage)])
        rows.append({"zoom": z, "sampled": len(values), "nonempty": sum(value > 0 for value in values),
                     "estimated_archive_bytes": estimated, "estimated_payload_bytes": population * statistics.mean(values)})
        total += estimated
        variance += var
        payload_total += population * statistics.mean(values)
        output_tiles += population * sum(value > 0 for value in values) / len(values)
    total += fixed_archive
    shard_total += 4096 * 5 * shard_page_size
    interval = [max(0., total - 1.96 * math.sqrt(variance)), total + 1.96 * math.sqrt(variance)]
    shard_interval = [max(0., shard_total - 1.96 * math.sqrt(shard_variance)), shard_total + 1.96 * math.sqrt(shard_variance)]
    archive_budget = interval[1] * 1.2
    result = {"sample": manifest, "candidate": str(a.candidate.resolve()), "sample_payload_bytes": payload,
        "sample_database_bytes": a.candidate.stat().st_size, "sample_nonempty_tiles": nonempty,
        "sample_archive_page_size": page_size, "sample_shard_page_size": shard_page_size,
        "observed_overhead_bytes_per_output_tile": overhead_per_output, "strata": rows,
        "observed_shard_overhead_bytes_per_output_tile": shard_overhead,
        "estimated_payload_bytes": payload_total, "estimated_archive_bytes": total,
        "approximate_sampling_95pct_archive_bytes": interval, "estimated_nonempty_tiles": output_tiles,
        "estimated_shard_bytes": shard_total, "approximate_sampling_95pct_shard_bytes": shard_interval,
        "archive_planning_bytes_with_20pct_margin": archive_budget,
        "shards_plus_merge_planning_bytes": 1.2 * (total + shard_total + 1.96 * math.sqrt(combined_variance)),
        "index_sort_scratch_reserve_bytes": max(4 * 2**30, output_tiles * 64),
        "limits": "Sampling interval only; page-fill estimate is empirical. Add previous final archive, logs and separately stored inputs."}
    (a.out / "estimate.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "analyze"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--shard", type=Path, help="Corresponding 4 KiB sample shard, to estimate retained-shard overhead separately")
    args = parser.parse_args()
    if args.action == "analyze" and args.candidate is None:
        parser.error("analyze requires --candidate")
    (prepare if args.action == "prepare" else analyze)(args)
