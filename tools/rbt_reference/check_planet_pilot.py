"""Check bounded planet pilot output against RBT, including seams and peak schema.

Original archives are opened read-only; writes only the requested JSON report.
Seam distances use MVT units (8 units = one pixel at a 512-pixel tile).
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

import numpy as np
from shapely.geometry import LineString, shape

from analyze_contours import decode, open_ro
from compare import ice_agreement
from compare_global import read_tile, validate_candidate


def crossings(layer, axis, edge):
    result = defaultdict(list)
    boundary = LineString([(edge, 0), (edge, 4096)] if axis == 0 else [(0, edge), (4096, edge)])
    for feature in (layer or {}).get("features", []):
        props = feature["properties"]
        intersection = shape(feature["geometry"]).intersection(boundary)
        for part in getattr(intersection, "geoms", [intersection]):
            if part.geom_type == "Point":
                result[(props["ele"], props["is_ice"])].append(part.coords[0][1 - axis])
    return result


def compare_edges(a, b):
    distances = []
    unmatched = 0
    for key in a.keys() | b.keys():
        aa, bb = sorted(a[key]), sorted(b[key])
        # One-to-one nearest matches, preserving duplicate crossings.
        for value in aa:
            if not bb:
                unmatched += 1
                continue
            i = min(range(len(bb)), key=lambda i: abs(value - bb[i]))
            distances.append(abs(value - bb.pop(i)))
        unmatched += len(bb)
    return distances, unmatched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--regions", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    a = parser.parse_args()
    regions = json.loads(a.regions.read_text())
    keys = {tuple(k) for r in regions.values() for k in r["keys"]}
    layers, peaks, violations = {}, Counter(), []
    with open_ro(a.candidate.resolve().as_posix()) as candidate, open_ro(a.reference.resolve().as_posix()) as reference:
        assert candidate.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        metadata = dict(candidate.execute("SELECT name,value FROM metadata"))
        schema = validate_candidate(candidate, {"regions": regions})
        for key in sorted(keys):
            blob = read_tile(candidate, *key)
            layers[key] = decode(blob, "contours") if blob else None
            peak_layer = decode(blob, "peaks") if blob else None
            for i, feature in enumerate((peak_layer or {}).get("features", [])):
                p = feature["properties"]
                peaks["features_including_buffer_duplicates"] += 1
                peaks[p.get("ele_source", "missing_elevation")] += 1
                peaks[p.get("class", "missing_class")] += 1
                xy = feature["geometry"]["coordinates"]
                if (feature["geometry"]["type"] != "Point" or p.get("rank") != i + 1
                        or p.get("class") not in ("peak", "volcano")
                        or not all(-80 <= value <= 4176 for value in xy)
                        or ("ele" in p and (type(p["ele"]) is not int or p.get("ele_source") not in ("osm", "dem")))):
                    violations.append([*key, feature.get("id"), "peak schema/rank/coordinates"])
        seams = {}
        for name, region in regions.items():
            selected = {tuple(k) for k in region["keys"]}
            distances, unmatched, pairs, wrap_pairs = [], 0, 0, 0
            ice = np.zeros(4)
            for z, x, y in sorted(selected):
                layer = layers[z, x, y]
                ref_blob = read_tile(reference, z, x, y)
                ref = {layer_name: decode(ref_blob, layer_name) for layer_name in ("contours", "glacier_polygon")} if ref_blob else {}
                ice += ice_agreement(ref, layer)
                for axis, neighbor in ((0, (z, (x + 1) % (1 << z), y)), (1, (z, x, y + 1))):
                    if neighbor not in selected:
                        continue
                    pairs += 1
                    wrap_pairs += int(axis == 0 and x == (1 << z) - 1)
                    d, u = compare_edges(crossings(layer, axis, 4096), crossings(layers[neighbor], axis, 0))
                    distances.extend(d)
                    unmatched += u
            seams[name] = {"adjacent_pairs": pairs, "dateline_pairs": wrap_pairs,
                "matched_crossings": len(distances), "unmatched_crossings": unmatched,
                "over_one_pixel": sum(d > 8 for d in distances),
                "distance_units_p50_p95_max": np.percentile(distances, [50, 95, 100]).tolist() if distances else [],
                "ice_length_units": ice[0], "ice_inside_rbt_polygon_fraction": ice[1] / ice[0] if ice[0] else None,
                "land_outside_rbt_polygon_fraction": ice[3] / ice[2] if ice[2] else None}
            print(name, json.dumps(seams[name]), flush=True)
    report = {"candidate": str(a.candidate.resolve()), "metadata": metadata, "contour_schema": schema,
              "peaks": dict(peaks), "peak_violations": violations, "regions": seams}
    a.out.write_text(json.dumps(report, indent=2))
    if schema["violations"] or violations:
        raise SystemExit("Schema violations; see report")


if __name__ == "__main__":
    main()
