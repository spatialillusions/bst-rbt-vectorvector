"""Prepare and run the optional 10 m / z14 investigation without changing production code.

python tools/investigate_contour_detail.py prepare
python tools/investigate_contour_detail.py run --java <java.exe>
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import subprocess

REGIONS = {
    "iceland": (-21.65, 64.25), "alps": (7.7, 46.0), "himalayas": (86.9, 28.0),
    "andes": (-72.9, -49.3), "new-zealand": (171.5, -43.5), "greenland": (-42., 65.5),
    "netherlands": (5.1, 52.1), "sweden": (13.1, 63.3),
    "native-greenland": (-27.0, 68.48),
}


def prepare(a):
    a.out.mkdir(parents=True, exist_ok=False)
    manifest = {}
    for name, (lon, lat) in REGIONS.items():
        x = int((lon + 180) / 360 * 8192)
        y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * 8192)
        parents = [[13, xx, yy] for xx in range(x - 4, x + 4) for yy in range(y - 4, y + 4)]
        children = [[14, xx * 2 + dx, yy * 2 + dy] for _, xx, yy in parents for dx in (0, 1) for dy in (0, 1)]
        manifest[name] = {"center": [lon, lat], "z13": parents, "z14": children}
    for group in ("planet", "native"):
        names = [n for n in manifest if (n == "native-greenland") == (group == "native")]
        for z in (13, 14):
            keys = sorted({tuple(k) for n in names for k in manifest[n][f"z{z}"]})
            (a.out / f"{group}-z{z}.txt").write_text("".join(f"{z}/{x}/{y}\n" for z, x, y in keys))
    (a.out / "manifest.json").write_text(json.dumps(manifest, indent=2))


def run(a):
    variants = [("z13-base", 13, "14", "rbt"), ("z14-rbt28", 14, "28", "rbt"),
                ("z14-10m28", 14, "28", "10m"), ("z14-10m14", 14, "14", "10m"),
                ("z14-10m7", 14, "7", "10m")]
    jobs = [("planet", r, v) for r in range(2) for v in variants]
    random.Random(147).shuffle(jobs)
    # Regional native-data runs are quality probes, not replicated throughput measurements.
    jobs += [("native", 0, v) for v in variants]
    if a.action == "run-native-alps":
        manifest = json.loads((a.out / "manifest.json").read_text())
        for z in (13, 14):
            (a.out / f"native-alps-z{z}.txt").write_text("".join("/".join(map(str, t)) + "\n" for t in manifest["alps"][f"z{z}"]))
        jobs = [("native-alps", 0, v) for v in variants]
    with a.jar.open("rb") as f:
        jar_hash = hashlib.file_digest(f, "sha256").hexdigest()
    results = []
    if a.action == "run-native-alps":
        previous = json.loads((a.out / "runs.json").read_text())
        assert previous["jar_sha256"] == jar_hash
        results = previous["runs"]
    for group, rep, (name, zoom, sigma, levels) in jobs:
        stem = f"{group}-{name}-{rep}"
        dest = a.out / (stem + ".mbtiles")
        cmd = [a.java, "-Xms1g", "-Xmx16g", "-XX:+UseG1GC", "-cp", str(a.jar),
               "tools/ContourDetailProbe.java", str(a.out / f"{group}-z{zoom}.txt"),
               str(dest), sigma, levels, str(a.planet)]
        if group in ("native", "native-alps"):
            cmd.append(str(a.regional))
        (a.out / (stem + ".command.json")).write_text(json.dumps(cmd, indent=2))
        log = a.out / (stem + ".log")
        with log.open("w", encoding="utf-8") as f:
            subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, check=True)
        metrics = [json.loads(line.removeprefix("DETAIL_RESULT ")) for line in log.read_text(encoding="utf-8").splitlines()
                   if line.startswith("DETAIL_RESULT ")]
        assert len(metrics) == 1
        row = {"group": group, "variant": name, "repeat": rep, "zoom": zoom,
               "sigma": float(sigma), "levels": levels, "path": str(dest), **metrics[0]}
        results.append(row)
        (a.out / "runs.json").write_text(json.dumps({"jar_sha256": jar_hash, "runs": results}, indent=2))
        print(stem, metrics[0], flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("prepare", "run", "run-native-alps"))
    p.add_argument("--out", type=Path, default=Path("out/contour-detail-study"))
    p.add_argument("--java", default="java")
    p.add_argument("--jar", type=Path, default=Path("target/vectorvector-cli.jar"))
    p.add_argument("--planet", type=Path, default=Path("D:/Mapterhorn/mapterhorn.pmtiles"))
    p.add_argument("--regional", type=Path, default=Path("data/iceland/6-27-15.pmtiles"))
    args = p.parse_args()
    (prepare if args.action == "prepare" else run)(args)
