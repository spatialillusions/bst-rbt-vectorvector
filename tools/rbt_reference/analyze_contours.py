"""Analyse a contours layer in an mbtiles file and write a JSON + Markdown report.

Usage:
    python analyze_contours.py <mbtiles> [--layer contours] [--out docs/report] [--minzoom 9] [--maxzoom 14]
                               [--max-tiles-per-zoom N]

The functions here are reused by compare.py.
"""
import argparse
import collections
import gzip
import json
import math
import random
import sqlite3
from functools import reduce

import mapbox_vector_tile
import numpy as np

INDEX_SET = (800, 400, 200, 100, 50, 20)


def expected_index(ele):
    for d in INDEX_SET:
        if ele % d == 0:
            return d
    return None


def open_ro(path):
    return sqlite3.connect("file:%s?mode=ro" % path, uri=True)


def read_metadata(conn):
    return dict(conn.execute("SELECT name, value FROM metadata").fetchall())


def iter_tiles(conn, z, max_tiles=None, seed=1, window=None, only=None):
    """Yields (x, y, tile_bytes) with XYZ y. window=(x0,x1,y0,y1) restricts to XYZ range."""
    rows = conn.execute("SELECT tile_column, tile_row FROM tiles WHERE zoom_level=?", (z,)).fetchall()
    n = 2 ** z
    rows = [(c, n - 1 - r) for c, r in rows]
    if window:
        x0, x1, y0, y1 = window
        rows = [(x, y) for x, y in rows if x0 <= x <= x1 and y0 <= y <= y1]
    if only is not None:
        rows = [r for r in rows if r in only]
    rows.sort()
    if max_tiles and len(rows) > max_tiles:
        rows = sorted(random.Random(seed).sample(rows, max_tiles))
    for x, y in rows:
        data = conn.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                            (z, x, n - 1 - y)).fetchone()[0]
        yield x, y, data


def decode(data, layer):
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    t = mapbox_vector_tile.decode(data, default_options={"y_coord_down": True})
    return t.get(layer)


def lines_of(geom):
    if geom["type"] == "LineString":
        return [np.asarray(geom["coordinates"], dtype=float)]
    if geom["type"] == "MultiLineString":
        return [np.asarray(c, dtype=float) for c in geom["coordinates"]]
    return []


def line_metrics(pts):
    """Returns (length, n_segments, turn_angles_deg array)."""
    if len(pts) < 2:
        return 0.0, 0, np.zeros(0)
    d = np.diff(pts, axis=0)
    seg = np.hypot(d[:, 0], d[:, 1])
    nz = seg > 0
    d = d[nz]
    seg = seg[nz]
    if len(d) < 2:
        return float(seg.sum()), len(seg), np.zeros(0)
    a = np.arctan2(d[:, 1], d[:, 0])
    t = np.abs((np.diff(a) + math.pi) % (2 * math.pi) - math.pi)
    return float(seg.sum()), len(seg), np.degrees(t)


def pct(values, q):
    return float(np.percentile(values, q)) if len(values) else None


def analyze_zoom(conn, z, layer, max_tiles=None, window=None, only=None):
    keys = collections.defaultdict(set)
    eles = set()
    index_hist = collections.Counter()
    index_bad = collections.Counter()
    geom_types = collections.Counter()
    ice_len = land_len = 0.0
    ice_feat = land_feat = 0
    extents = set()
    minc, maxc = math.inf, -math.inf
    tiles = tiles_with_layer = 0
    per_tile = collections.defaultdict(list)
    for x, y, data in iter_tiles(conn, z, max_tiles, window=window, only=only):
        tiles += 1
        lyr = decode(data, layer)
        if not lyr or not lyr["features"]:
            continue
        tiles_with_layer += 1
        extents.add(lyr.get("extent"))
        total_len = 0.0
        segs = 0
        turns = []
        for f in lyr["features"]:
            p = f["properties"]
            for k, v in p.items():
                keys[k].add(type(v).__name__)
            ele = p.get("ele")
            if ele is not None:
                eles.add(ele)
                idx = p.get("index")
                index_hist[idx] += 1
                if idx is not None and int(ele) == ele and expected_index(int(ele)) != idx:
                    index_bad[(ele, idx)] += 1
            geom_types[f["geometry"]["type"]] += 1
            flen = 0.0
            for pts in lines_of(f["geometry"]):
                if len(pts):
                    minc = min(minc, pts.min())
                    maxc = max(maxc, pts.max())
                ln, ns, ta = line_metrics(pts)
                flen += ln
                segs += ns
                turns.append(ta)
            total_len += flen
            if p.get("is_ice") == 1:
                ice_len += flen
                ice_feat += 1
            else:
                land_len += flen
                land_feat += 1
        turns = np.concatenate(turns) if turns else np.zeros(0)
        per_tile["features_per_tile"].append(len(lyr["features"]))
        per_tile["length_per_tile"].append(total_len)
        if segs:
            per_tile["vertex_spacing"].append(total_len / segs)
        if len(turns):
            per_tile["turn_angle"].append(float(turns.mean()))
            per_tile["sharp_ratio"].append(float((turns > 45).mean()))
    ele_list = sorted(eles)
    diffs = [int(round(b - a)) for a, b in zip(ele_list, ele_list[1:])]
    interval = reduce(math.gcd, diffs) if diffs else None
    gcd_all = reduce(math.gcd, [abs(int(e)) for e in ele_list if e]) if ele_list else None
    smooth = {k: {"median": pct(v, 50), "p90": pct(v, 90)} for k, v in per_tile.items()}
    return {
        "zoom": z,
        "tiles": tiles,
        "tiles_with_layer": tiles_with_layer,
        "keys": {k: sorted(v) for k, v in keys.items()},
        "ele_min": ele_list[0] if ele_list else None,
        "ele_max": ele_list[-1] if ele_list else None,
        "ele_count": len(ele_list),
        "ele_interval_gcd_diffs": interval,
        "ele_gcd": gcd_all,
        "has_negative": any(e < 0 for e in ele_list),
        "has_zero": 0 in eles,
        "index_hist": {str(k): v for k, v in sorted(index_hist.items(), key=lambda kv: (kv[0] is None, kv[0]))},
        "index_mismatches": {"%s->%s" % k: v for k, v in index_bad.most_common(10)},
        "is_ice_features": {"ice": ice_feat, "land": land_feat},
        "is_ice_length_ratio": ice_len / (ice_len + land_len) if ice_len + land_len else None,
        "geometry_types": dict(geom_types),
        "extent": sorted(e for e in extents if e is not None),
        "coord_min": None if minc == math.inf else minc,
        "coord_max": None if maxc == -math.inf else maxc,
        "smoothness": smooth,
    }


def analyze(path, layer="contours", minzoom=0, maxzoom=None, max_tiles=None):
    conn = open_ro(path)
    meta = read_metadata(conn)
    zooms = [r[0] for r in conn.execute("SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level")]
    if maxzoom is not None:
        zooms = [z for z in zooms if z <= maxzoom]
    zooms = [z for z in zooms if z >= minzoom]
    vector_layers = None
    if "json" in meta:
        try:
            vector_layers = [l for l in json.loads(meta["json"]).get("vector_layers", []) if l["id"] == layer]
        except ValueError:
            pass
    per_zoom = []
    for z in zooms:
        r = analyze_zoom(conn, z, layer, max_tiles)
        print("z%d: %d tiles, %d with layer" % (z, r["tiles"], r["tiles_with_layer"]), flush=True)
        if r["tiles_with_layer"]:
            per_zoom.append(r)
    short_meta = {k: v for k, v in meta.items() if k != "json"}
    return {"file": path, "layer": layer, "metadata": short_meta, "vector_layers": vector_layers, "zooms": per_zoom}


def fmt(v, nd=2):
    if v is None:
        return "-"
    if isinstance(v, float):
        return ("%." + str(nd) + "f") % v
    return str(v)


def to_markdown(rep):
    out = ["# Contour analysis: `%s`" % rep["file"], "", "Layer: `%s`" % rep["layer"], "", "## Metadata", ""]
    for k, v in rep["metadata"].items():
        out.append("* `%s`: %s" % (k, (v[:200] + "…") if v and len(v) > 200 else v))
    out += ["", "`vector_layers` entry:", "", "```json", json.dumps(rep["vector_layers"], indent=1), "```", ""]
    out += ["## Schema and intervals per zoom", "",
            "| z | tiles | keys (types) | ele min..max | #ele | interval (gcd of diffs) | negative | zero | geom types | extent | coord min..max |",
            "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rep["zooms"]:
        keys = ", ".join("%s:%s" % (k, "/".join(v)) for k, v in sorted(r["keys"].items()))
        out.append("| %d | %d | %s | %s..%s | %d | %s | %s | %s | %s | %s | %s..%s |" % (
            r["zoom"], r["tiles_with_layer"], keys, fmt(r["ele_min"]), fmt(r["ele_max"]), r["ele_count"],
            fmt(r["ele_interval_gcd_diffs"]), r["has_negative"], r["has_zero"],
            ", ".join("%s:%d" % kv for kv in r["geometry_types"].items()), r["extent"],
            fmt(r["coord_min"], 0), fmt(r["coord_max"], 0)))
    out += ["", "## `index` and `is_ice`", "",
            "| z | index histogram | index mismatches (ele->index) | ice/land features | ice length share |",
            "|---|---|---|---|---|"]
    for r in rep["zooms"]:
        out.append("| %d | %s | %s | %d / %d | %s |" % (
            r["zoom"], ", ".join("%s:%d" % kv for kv in r["index_hist"].items()),
            ", ".join("%s:%d" % kv for kv in r["index_mismatches"].items()) or "none",
            r["is_ice_features"]["ice"], r["is_ice_features"]["land"], fmt(r["is_ice_length_ratio"], 3)))
    out += ["", "## Smoothness (per-tile values; median / p90)", "",
            "| z | vertex_spacing | turn_angle (deg) | sharp_ratio (>45°) | length_per_tile | features_per_tile |",
            "|---|---|---|---|---|---|"]
    for r in rep["zooms"]:
        s = r["smoothness"]
        cell = lambda k: "%s / %s" % (fmt(s.get(k, {}).get("median"), 3 if k == "sharp_ratio" else 1),
                                      fmt(s.get(k, {}).get("p90"), 3 if k == "sharp_ratio" else 1))
        out.append("| %d | %s | %s | %s | %s | %s |" % (
            r["zoom"], cell("vertex_spacing"), cell("turn_angle"), cell("sharp_ratio"),
            cell("length_per_tile"), cell("features_per_tile")))
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mbtiles")
    ap.add_argument("--layer", default="contours")
    ap.add_argument("--out", default=None, help="output path without extension")
    ap.add_argument("--minzoom", type=int, default=0)
    ap.add_argument("--maxzoom", type=int, default=None)
    ap.add_argument("--max-tiles-per-zoom", type=int, default=None)
    a = ap.parse_args()
    rep = analyze(a.mbtiles, a.layer, a.minzoom, a.maxzoom, a.max_tiles_per_zoom)
    out = a.out or a.mbtiles.rsplit(".", 1)[0] + "-report"
    with open(out + ".json", "w", encoding="utf-8") as f:
        json.dump(rep, f, indent=1)
    with open(out + ".md", "w", encoding="utf-8") as f:
        f.write(to_markdown(rep))
    print("wrote %s.json / .md" % out)


if __name__ == "__main__":
    main()
