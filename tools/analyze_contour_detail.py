"""Summarize the detail investigation and render comparable ground footprints."""
from contextlib import closing
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import sqlite3
import statistics
import struct
import sys

import mapbox_vector_tile
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from shapely.geometry import LineString, box

sys.path.insert(0, str(Path(__file__).parent / "rbt_reference"))
from analyze_contours import line_metrics
from compare import positional_agreement

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "out/contour-detail-study")
VARIANTS = ["z13-base", "z14-rbt28", "z14-10m28", "z14-10m14", "z14-10m7"]


def open_ro(path):
    return sqlite3.connect(f"file:{Path(path).resolve().as_posix()}?mode=ro", uri=True)


def tile(conn, z, x, y):
    row = conn.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, (1 << z) - 1 - y)).fetchone()
    if not row:
        return None
    blob = row[0]
    return mapbox_vector_tile.decode(gzip.decompress(blob) if blob[:2] == b"\x1f\x8b" else blob,
                                     default_options={"y_coord_down": True}).get("contours")


def digest(path):
    h, count, size = hashlib.sha256(), 0, 0
    with closing(open_ro(path)) as c:
        assert c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        for z, x, y, blob in c.execute("SELECT * FROM tiles ORDER BY zoom_level,tile_column,tile_row"):
            h.update(struct.pack("<4i", z, x, y, len(blob)))
            h.update(blob)
            count += 1
            size += len(blob)
    return {"tiles": count, "compressed_payload_bytes": size, "sha256": h.hexdigest()}


def sample_stats(conn, keys, dense):
    sampled = random.Random(147).sample(keys, min(16, len(keys)))
    spacing, angles, sharpness = [], [], []
    features = violations = minor10 = 0
    for z, x, y in sampled:
        layer = tile(conn, z, x, y)
        if not layer:
            continue
        assert layer["extent"] == 4096
        length = segments = 0
        turns = []
        for f in layer["features"]:
            features += 1
            p, g = f["properties"], f["geometry"]
            e = p["ele"]
            expected = next((d for d in (800, 400, 200, 100, 50, 20, 10) if e % d == 0), None)
            violations += not (set(p) == {"ele", "index", "is_ice"} and all(type(v) is int for v in p.values())
                               and e != 0 and e % 10 == 0 and (dense or e % 20 == 0 or e % 50 == 0)
                               and p["index"] == expected and p["is_ice"] in (0, 1))
            minor10 += p["index"] == 10
            parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
            for coords in parts:
                a = np.asarray(coords)
                violations += not (len(a) >= 2 and a.min() >= -80 and a.max() <= 4176)
                ln, ns, ta = line_metrics(a)
                length += ln
                segments += ns
                turns.append(ta)
        if segments:
            spacing.append(length / segments)
        t = np.concatenate(turns) if turns else np.zeros(0)
        if len(t):
            angles.append(float(t.mean()))
            sharpness.append(float((t > 45).mean()))
    return {"sampled_keys": len(sampled), "features_checked": features, "violations": violations,
            "index10_features": minor10,
            "median_vertex_spacing": statistics.median(spacing) if spacing else None,
            "median_turn_degrees": statistics.median(angles) if angles else None,
            "median_sharp_fraction": statistics.median(sharpness) if sharpness else None}


def parent_layer(conn, x, y, z):
    if z == 13:
        return tile(conn, 13, x, y)
    features = []
    for dx in (0, 1):
        for dy in (0, 1):
            layer = tile(conn, 14, x * 2 + dx, y * 2 + dy)
            for f in layer["features"] if layer else []:
                g = f["geometry"]
                parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
                clipped = []
                for part in parts:
                    g = LineString(part).intersection(box(0, 0, 4096, 4096))
                    pieces = list(g.geoms) if hasattr(g, "geoms") else [g]
                    clipped.extend([[(px + dx * 4096) / 2, (py + dy * 4096) / 2] for px, py in p.coords]
                                   for p in pieces if p.geom_type == "LineString" and not p.is_empty)
                if clipped:
                    features.append({"properties": f["properties"], "geometry": {"type": "MultiLineString", "coordinates": clipped}})
    return {"features": features, "extent": 4096}


def render_and_compare(name, center, sources, out, parents):
    lon, lat = center
    x = int((lon + 180) / 360 * 8192)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * 8192)
    layers = {v: parent_layer(c, x, y, 13 if v == "z13-base" else 14) for v, c in sources.items()}
    selection = "center tile"
    if not any(l and l["features"] for l in layers.values()):
        # A blank lowland center is uninformative: deterministic selection from the same window.
        _, x, y = max(parents, key=lambda t: len(parent_layer(sources["z14-10m28"], t[1], t[2], 14)["features"]))
        layers = {v: parent_layer(c, x, y, 13 if v == "z13-base" else 14) for v, c in sources.items()}
        selection = "most populated 10m28 parent in window (center empty)"
    fig, axes = plt.subplots(1, 4, figsize=(20, 5.4), dpi=140)
    labels = [("z13-base", "Existing z13, overzoomed (20 m display)"),
              ("z14-10m28", "10 m at z14, sigma 28 (same ground blur)"),
              ("z14-10m14", "10 m at z14, sigma 14 (half ground blur)"),
              ("z14-10m7", "10 m at z14, sigma 7 (quarter ground blur)")]
    for ax, (variant, label) in zip(axes, labels):
        layer = layers[variant]
        for f in layer["features"] if layer else []:
            e = f["properties"]["ele"]
            if variant == "z13-base" and e % 20:
                continue  # Match the current style at display z14, not the extra hidden 50 m levels.
            g = f["geometry"]
            parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
            for part in parts:
                a = np.asarray(part)
                major, new = e % 100 == 0, e % 20 != 0
                ax.plot(a[:, 0], a[:, 1], color="#985258", lw=.85 if major else .38,
                        alpha=.6 if new else 1.)
        ax.set_xlim(0, 4096)
        ax.set_ylim(4096, 0)
        ax.set_aspect("equal")
        ax.set_xticks([])
        ax.set_yticks([])
        ax.set_title(label, fontsize=9)
    fig.suptitle(f"{name}: identical ground footprint (z13/{x}/{y}); {selection}; no OSM ice mask", fontsize=11)
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)
    agreement = {}
    for v in VARIANTS[1:]:
        common = {"features": [f for f in (layers[v] or {"features": []})["features"]
                               if f["properties"]["ele"] % 20 == 0 or f["properties"]["ele"] % 50 == 0]}
        length, near = positional_agreement(layers["z13-base"], common, 8.)
        agreement[v] = near / length if length else None
    return agreement


def main():
    manifest = json.loads((ROOT / "manifest.json").read_text())
    runs = json.loads((ROOT / "runs.json").read_text())
    for row in runs["runs"]:
        row["output"] = digest(row["path"])
    for group in ("planet", "native-alps"):
        for v in VARIANTS:
            hashes = {r["output"]["sha256"] for r in runs["runs"] if r["group"] == group and r["variant"] == v}
            assert len(hashes) == 1, (group, v, hashes)
    report = {"jar_sha256": runs["jar_sha256"], "runs": runs["runs"], "regions": {}}
    for group in ("planet", "native-alps"):
        selected = {r["variant"]: r for r in runs["runs"] if r["group"] == group and r["repeat"] == 0}
        conns = {v: open_ro(r["path"]) for v, r in selected.items()}
        try:
            names = [n for n in manifest if n != "native-greenland"] if group == "planet" else ["alps"]
            for n in names:
                print("Analyzing", group, n, flush=True)
                entry = {"variants": {}}
                for v, c in conns.items():
                    keys = manifest[n]["z13" if v == "z13-base" else "z14"]
                    entry["variants"][v] = sample_stats(c, keys, "10m" in v)
                    count = size = 0
                    for z, x, y in keys:
                        r = c.execute("SELECT length(tile_data) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, (1 << z) - 1 - y)).fetchone()
                        if r:
                            count += 1
                            size += r[0]
                    entry["variants"][v].update(tiles=count, requested_tiles=len(keys), compressed_payload_bytes=size)
                retained = 0
                for z, x, y in manifest[n]["z14"]:
                    sparse = tile(conns["z14-rbt28"], z, x, y)
                    dense = tile(conns["z14-10m28"], z, x, y)
                    before = sparse["features"] if sparse else []
                    after = [f for f in (dense["features"] if dense else [])
                             if f["properties"]["ele"] % 20 == 0 or f["properties"]["ele"] % 50 == 0]
                    assert before == after, (group, n, z, x, y)
                    retained += 1
                entry["retained_level_tile_checks"] = retained
                entry["rendered_parent_common_level_agreement"] = render_and_compare(f"{group}/{n}", manifest[n]["center"], conns, ROOT / f"{group}-{n}-comparison.png", manifest[n]["z13"])
                report["regions"][f"{group}/{n}"] = entry
        finally:
            for c in conns.values():
                c.close()
    (ROOT / "analysis.json").write_text(json.dumps(report, indent=2))
    checked = sum(s["features_checked"] for r in report["regions"].values() for s in r["variants"].values())
    failures = sum(s["violations"] for r in report["regions"].values() for s in r["variants"].values())
    print("sampled feature checks", checked, "violations", failures, flush=True)
    assert failures == 0


if __name__ == "__main__":
    main()
