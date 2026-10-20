"""Compare a candidate contours mbtiles against the RBT reference subset.

Usage:
    python compare.py <reference.mbtiles> <candidate.mbtiles> [--out out/compare] [--max-tiles 300]
                      [--png-samples 4] [--label name]

Writes <out>/compare-report.md and .json, plus side-by-side PNGs (reference red, candidate blue, ice dashed,
reference glacier_polygon shaded) for a few sample tiles per zoom.
"""
import argparse
import json
import math
import os
import random
import sys

import numpy as np
from shapely.affinity import scale
from shapely.geometry import LineString, MultiLineString, shape
from shapely.ops import unary_union
from shapely.strtree import STRtree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_contours import analyze_zoom, decode, expected_index, fmt, open_ro  # noqa: E402

SMOOTH_KEYS = ("vertex_spacing", "turn_angle", "sharp_ratio")


def tile_keys(conn, z):
    n = 2 ** z
    return {(c, n - 1 - r) for c, r in conn.execute("SELECT tile_column, tile_row FROM tiles WHERE zoom_level=?", (z,))}


def get_tile(conn, z, x, y):
    row = conn.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                       (z, x, 2 ** z - 1 - y)).fetchone()
    return row[0] if row else None


def lines_by(layer, ice=None):
    """{ele: [LineString]} for a decoded contours layer, optionally only ice or land."""
    out = {}
    if not layer:
        return out
    for f in layer["features"]:
        p = f["properties"]
        if ice is not None and bool(p.get("is_ice")) != ice:
            continue
        g = f["geometry"]
        parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"] if g["type"] == "MultiLineString" else []
        for c in parts:
            if len(c) >= 2:
                out.setdefault(p["ele"], []).append(LineString(c))
    return out


def glacier_geometry(layer):
    if not layer:
        return None
    extent = layer.get("extent", 4096)
    if not isinstance(extent, (int, float)) or not math.isfinite(extent) or extent <= 0:
        raise ValueError("Invalid glacier polygon layer extent")
    polys = []
    for f in layer["features"]:
        try:
            g = shape(f["geometry"])
            if not g.is_valid:
                g = g.buffer(0)
            polys.append(g)
        except Exception:
            pass
    geometry = unary_union(polys) if polys else None
    # MVT layers have independent extents: RBT's z13 glacier polygons use
    # 16384 while its contour lines use 4096. Compare in contour coordinates.
    return scale(geometry, xfact=4096 / extent, yfact=4096 / extent, origin=(0, 0)) if geometry is not None else None


def positional_agreement(ref_layer, cand_layer, d):
    """Share of candidate length within d of a reference line with the same ele; tiles clipped to 0..4096."""
    ref = lines_by(ref_layer)
    cand = lines_by(cand_layer)
    total = near = 0.0
    box = LineString([(0, 0), (4096, 0), (4096, 4096), (0, 4096), (0, 0)]).convex_hull
    for ele, lines in cand.items():
        cl = unary_union(lines).intersection(box)
        length = cl.length
        if length == 0:
            continue
        total += length
        if ele in ref:
            zone = unary_union(ref[ele]).buffer(d)
            near += cl.intersection(zone).length
    return total, near


def ice_agreement(ref_tile, cand_layer):
    """(ice length, ice length inside reference glacier_polygon, land length, land length outside)."""
    glacier = glacier_geometry(ref_tile.get("glacier_polygon"))
    box = LineString([(0, 0), (4096, 0), (4096, 4096), (0, 4096), (0, 0)]).convex_hull
    res = [0.0, 0.0, 0.0, 0.0]
    for ice, idx in ((True, 0), (False, 2)):
        lines = [l for ls in lines_by(cand_layer, ice).values() for l in ls]
        if not lines:
            continue
        geom = unary_union(lines).intersection(box)
        res[idx] += geom.length
        if glacier is not None and not glacier.is_empty:
            inside = geom.intersection(glacier.buffer(8)).length if ice else geom.difference(glacier.buffer(-8) if glacier.area > 0 else glacier).length
            res[idx + 1] += inside
        else:
            res[idx + 1] += 0.0 if ice else geom.length
    return res


def render(path, ref_tile, cand_layer, title):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Polygon as MplPolygon

    fig, axes = plt.subplots(1, 2, figsize=(12, 6.2), dpi=110)
    glacier = glacier_geometry(ref_tile.get("glacier_polygon"))
    for ax, layer, color, name in ((axes[0], ref_tile.get("contours"), "#c0392b", "RBT"), (axes[1], cand_layer, "#1f5fbf", "candidate")):
        if glacier is not None and not glacier.is_empty:
            for poly in getattr(glacier, "geoms", [glacier]):
                if poly.geom_type == "Polygon":
                    ax.add_patch(MplPolygon(np.asarray(poly.exterior.coords), closed=True, color="#bfe6f7", zorder=0))
        if layer:
            for f in layer["features"]:
                p = f["properties"]
                g = f["geometry"]
                parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
                major = p.get("index", 0) >= 100
                for c in parts:
                    a = np.asarray(c)
                    ax.plot(a[:, 0], a[:, 1], color=color, lw=1.1 if major else 0.5,
                            ls="--" if p.get("is_ice") else "-", zorder=2)
        ax.set_xlim(-80, 4176)
        ax.set_ylim(4176, -80)
        ax.set_aspect("equal")
        ax.set_title("%s %s" % (name, title), fontsize=9)
        ax.plot([0, 4096, 4096, 0, 0], [0, 0, 4096, 4096, 0], color="#999", lw=0.5)
        ax.set_xticks([])
        ax.set_yticks([])
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reference")
    ap.add_argument("candidate")
    ap.add_argument("--out", default="out/compare")
    ap.add_argument("--max-tiles", type=int, default=300)
    ap.add_argument("--png-samples", type=int, default=4)
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    ref = open_ro(a.reference)
    cand = open_ro(a.candidate)

    ref_z = {r[0] for r in ref.execute("SELECT DISTINCT zoom_level FROM tiles")}
    cand_z = {r[0] for r in cand.execute("SELECT DISTINCT zoom_level FROM tiles")}
    report = {"reference": a.reference, "candidate": a.candidate, "label": a.label, "zooms": []}
    checks = []

    ref_contour_zooms = []
    for z in sorted(ref_z):
        if z < 9:
            continue
        ref_contour_zooms.append(z)
    rng = random.Random(7)
    for z in sorted(set(ref_contour_zooms) | {z for z in cand_z if z >= 9}):
        common = sorted(tile_keys(ref, z) & tile_keys(cand, z))
        sample = common if len(common) <= a.max_tiles else sorted(rng.sample(common, a.max_tiles))
        # smoothness and schema on the same tiles in both files
        only = set(common) if common else None
        r_stats = analyze_zoom(ref, z, "contours", a.max_tiles, only=only) if z in ref_z else None
        c_stats = analyze_zoom(cand, z, "contours", a.max_tiles, only=only) if z in cand_z else None
        entry = {"zoom": z, "common_tiles": len(common), "sampled": len(sample), "ref": r_stats, "cand": c_stats}
        if r_stats and c_stats and r_stats["tiles_with_layer"] and c_stats["tiles_with_layer"]:
            entry["ratios"] = {}
            for k in SMOOTH_KEYS:
                rv = r_stats["smoothness"].get(k, {}).get("median")
                cv = c_stats["smoothness"].get(k, {}).get("median")
                if k == "sharp_ratio":
                    # ratios of tiny numbers are meaningless; compare with an additive floor
                    rv = (rv or 0) + 0.005
                    cv = (cv or 0) + 0.005
                entry["ratios"][k] = (cv / rv) if rv else None
        total = near = 0.0
        ice = [0.0, 0.0, 0.0, 0.0]
        d = 2 * 4096 / 512.0  # 2 px at 512 px tiles
        pngs = []
        for i, (x, y) in enumerate(sample):
            rt_raw = get_tile(ref, z, x, y)
            ct_raw = get_tile(cand, z, x, y)
            from analyze_contours import mapbox_vector_tile, gzip  # reuse imports
            rdata = gzip.decompress(rt_raw) if rt_raw[:2] == b"\x1f\x8b" else rt_raw
            ref_tile = mapbox_vector_tile.decode(rdata, default_options={"y_coord_down": True})
            cand_layer = decode(ct_raw, "contours")
            t, n = positional_agreement(ref_tile.get("contours"), cand_layer, d)
            total += t
            near += n
            for k, v in enumerate(ice_agreement(ref_tile, cand_layer)):
                ice[k] += v
            if i < a.png_samples or (ref_tile.get("glacier_polygon") and len(pngs) < a.png_samples * 2):
                path = os.path.join(a.out, "z%d-%d-%d.png" % (z, x, y))
                render(path, ref_tile, cand_layer, "z%d/%d/%d %s" % (z, x, y, a.label))
                pngs.append(path)
        entry["positional_share"] = near / total if total else None
        entry["ice_inside_share"] = ice[1] / ice[0] if ice[0] else None
        entry["land_outside_share"] = ice[3] / ice[2] if ice[2] else None
        entry["pngs"] = pngs
        report["zooms"].append(entry)
        print("z%d: %d common tiles, positional %.3f, ice-inside %s" % (
            z, len(common), entry["positional_share"] or 0, fmt(entry["ice_inside_share"], 3)), flush=True)

    # exact-match checks
    ref_zooms = sorted(e["zoom"] for e in report["zooms"] if e["ref"] and e["ref"]["tiles_with_layer"])
    cand_zooms = sorted(e["zoom"] for e in report["zooms"] if e["cand"] and e["cand"]["tiles_with_layer"])
    checks.append(("zooms with contours", ref_zooms == cand_zooms, "ref %s, cand %s" % (ref_zooms, cand_zooms)))
    for e in report["zooms"]:
        r, c = e["ref"], e["cand"]
        if not (r and c and r["tiles_with_layer"] and c["tiles_with_layer"]):
            continue
        z = e["zoom"]
        checks.append(("z%d ele interval" % z, r["ele_interval_gcd_diffs"] == c["ele_interval_gcd_diffs"],
                       "ref %s, cand %s" % (r["ele_interval_gcd_diffs"], c["ele_interval_gcd_diffs"])))
        rs = set(r["index_hist"])
        cs = set(c["index_hist"])
        checks.append(("z%d index values" % z, cs <= rs, "ref %s, cand %s" % (sorted(rs), sorted(cs))))
        checks.append(("z%d keys/types" % z, r["keys"] == c["keys"], "ref %s, cand %s" % (r["keys"], c["keys"])))
        checks.append(("z%d index consistent" % z, not c["index_mismatches"], str(c["index_mismatches"])))
        for k, v in (e.get("ratios") or {}).items():
            checks.append(("z%d %s ratio" % (z, k), v is not None and 0.8 <= v <= 1.25, fmt(v, 3)))
        if e["ice_inside_share"] is not None:
            checks.append(("z%d is_ice inside RBT glaciers" % z, e["ice_inside_share"] > 0.8, fmt(e["ice_inside_share"], 3)))
    report["checks"] = [{"name": n, "ok": ok, "detail": d} for n, ok, d in checks]

    with open(os.path.join(a.out, "compare-report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1, default=str)
    lines = ["# Contour comparison %s" % a.label, "", "Reference: `%s`  " % a.reference, "Candidate: `%s`" % a.candidate, "",
             "## Checks", "", "| check | ok | detail |", "|---|---|---|"]
    for n, ok, d in checks:
        lines.append("| %s | %s | %s |" % (n, "✅" if ok else "❌", d.replace("|", "/")))
    lines += ["", "## Per zoom", "",
              "| z | tiles | vertex_spacing ref/cand | turn_angle ref/cand | sharp_ratio ref/cand | positional (2 px) | ice inside RBT glacier | land outside |",
              "|---|---|---|---|---|---|---|---|"]
    for e in report["zooms"]:
        r, c = e["ref"], e["cand"]

        def pair(k):
            rv = r["smoothness"].get(k, {}).get("median") if r and r["tiles_with_layer"] else None
            cv = c["smoothness"].get(k, {}).get("median") if c and c["tiles_with_layer"] else None
            return "%s / %s" % (fmt(rv, 3 if k == "sharp_ratio" else 1), fmt(cv, 3 if k == "sharp_ratio" else 1))
        lines.append("| %d | %d | %s | %s | %s | %s | %s | %s |" % (
            e["zoom"], e["common_tiles"], pair("vertex_spacing"), pair("turn_angle"), pair("sharp_ratio"),
            fmt(e["positional_share"], 3), fmt(e["ice_inside_share"], 3), fmt(e["land_outside_share"], 3)))
    lines += ["", "## Sample renders", ""]
    for e in report["zooms"]:
        for p in e["pngs"]:
            lines.append("![%s](%s)" % (os.path.basename(p), os.path.basename(p)))
    with open(os.path.join(a.out, "compare-report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    failed = [n for n, ok, _ in checks if not ok]
    print("%d checks, %d failed: %s" % (len(checks), len(failed), ", ".join(failed)))


if __name__ == "__main__":
    main()
