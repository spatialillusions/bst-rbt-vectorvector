"""Global-feature pilots: prepare fixed workloads, then run recovery/sustained and quality stages."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys


REGIONS = {"iceland": (-16.7, 64.1), "alps": (7.7, 46.), "himalayas": (86.9, 28.),
           "netherlands": (5.1, 52.1), "greenland": (-42., 65.5), "andes": (-72.9, -49.3),
           "new-zealand": (171.5, -43.5), "sweden": (13.1, 63.3)}


def xy(lon, lat, z):
    n = 1 << z
    return int((lon + 180) / 360 * n) % n, int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)


def prepare(a):
    a.out.mkdir(parents=True, exist_ok=True)
    jar = a.out / "pilot-cli.jar"
    if jar.exists():
        raise ValueError("Frozen pilot executable already exists; use a fresh output directory")
    roots = {xy(lon, lat, 6) for lon, lat in REGIONS.values()}
    tiles = sorted((z, x, y) for rx, ry in roots for z in range(9, 14)
                   for x in range(rx << (z - 6), (rx + 1) << (z - 6))
                   for y in range(ry << (z - 6), (ry + 1) << (z - 6)))
    (a.out / "sustained.tiles.txt").write_text("".join(f"{z}/{x}/{y}\n" for z, x, y in tiles))
    selected = {}
    centers = {**REGIONS, "antarctica": (-65., -66.5), "antarctica-dateline": (179.99, -84.),
               "fiji-dateline": (179.99, -16.75), "chukotka-dateline": (179.99, 65.),
               "north-limit": (-40., 85.05), "south-limit": (-65., -85.05)}
    for name, (lon, lat) in centers.items():
        keys = []
        for z in range(9, 14):
            x, y = xy(lon, lat, z)
            n = 1 << z
            for xx in range(x - 2, x + 2):
                for yy in range(y - 2, y + 2):
                    if 0 <= yy < n:
                        keys.append([z, xx % n, yy])
        selected[name] = {"center": [lon, lat], "keys": keys}
    quality = sorted({tuple(k) for r in selected.values() for k in r["keys"]})
    (a.out / "quality.tiles.txt").write_text("".join(f"{z}/{x}/{y}\n" for z, x, y in quality))
    (a.out / "quality-regions.json").write_text(json.dumps(selected, indent=2))
    shutil.copy2("target/vectorvector-cli.jar", jar)
    with jar.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    (a.out / "pilot-manifest.json").write_text(json.dumps({"jar_sha256": digest, "roots": sorted(roots),
        "sustained_requested_tiles": len(tiles), "quality_requested_tiles": len(quality)}, indent=2))
    print("prepared", len(roots), "full roots", len(tiles), "sustained tiles", len(quality), "quality tiles", flush=True)


def command(a, name, gc):
    args = [str(a.java), "-Xms1g", "-Xmx16g", "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError",
        f'-Xlog:gc*:file="{(a.out / (gc + ".log")).resolve().as_posix()}":time,uptime,level,tags', "-jar", str(a.jar),
        "--profile", "rbt", "--dataFormat", "mapterhorn", "--pmtiles", str(a.elevation),
        "--glaciers", str(a.osm), "--peaks", "--tileList", str(a.out / f"{name}.tiles.txt"),
        "-minZ", "6", "-maxZ", "13", "-minX", "0", "-maxX", "63", "-minY", "0", "-maxY", "63",
        "--parallelism", "8", "--elevationCacheTiles", "512", "--glacierCacheTiles", "1024",
        "--resumableShards", "--shardsX", "64", "--shardsY", "64", "-f", "mbtiles", "-o", str(a.out / f"{name}-tiles")]
    if a.glacier_wkb is not None:
        args += ["--glacierWkb", str(a.glacier_wkb)]
    return args


def run(a):
    if not a.osm.is_file() or not a.osm.with_suffix(".json").is_file():
        raise ValueError("Prepare and validate the global feature PBF first")
    manifest = json.loads((a.out / "pilot-manifest.json").read_text())
    a.jar = a.out / manifest.get("jar_file", "pilot-cli.jar")
    with a.jar.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    assert digest == manifest["jar_sha256"]
    base = [sys.executable, "tools/monitor_job.py", "--min-available-mib", "1024"]
    if a.action == "sustained":
        work = a.out / "sustained-tiles/.shards"
        subprocess.run(base + ["--out", str(a.out / "sustained-interrupted"), "--abort-after-done", "2",
            "--shard-dir", str(work), "--"] + command(a, "sustained", "sustained-first-gc"), check=True)
        stamps = {str(p): (p.stat().st_size, p.stat().st_mtime_ns) for p in work.glob("contours/*.mbtiles")
                  if (work / (p.stem + ".done")).exists()}
        assert len(stamps) >= 2
        (a.out / "before-resume-stamps.json").write_text(json.dumps(stamps, indent=2))
        subprocess.run(base + ["--out", str(a.out / "sustained-resumed"), "--"] +
                       command(a, "sustained", "sustained-resumed-gc"), check=True)
        assert all((Path(p).stat().st_size, Path(p).stat().st_mtime_ns) == tuple(s) for p, s in stamps.items())
        (a.out / "resume-verified.json").write_text(json.dumps({"completed_shards_reused": len(stamps)}, indent=2))
    else:
        subprocess.run(base + ["--out", str(a.out / "quality-run"), "--"] + command(a, "quality", "quality-gc"), check=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("prepare", "sustained", "quality"))
    p.add_argument("--out", type=Path, default=Path("out/planet-pilot-20261017"))
    p.add_argument("--java", type=Path, default=Path("java"), help="JDK 25 executable; defaults to PATH (source env.ps1/env.sh first)")
    p.add_argument("--elevation", type=Path, default=Path("D:/Mapterhorn/mapterhorn.pmtiles"))
    p.add_argument("--osm", type=Path, default=Path("out/planet-pilot-20261017/planet-features.osm.pbf"))
    p.add_argument("--glacier-wkb", type=Path, help="Additional OSM ice-sheet WKB input")
    args = p.parse_args()
    (prepare if args.action == "prepare" else run)(args)
