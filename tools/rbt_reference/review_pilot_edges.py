"""Review crossing outliers using buffered geometry, and check RBT's own ice masks.

Nearly tangent lines can have distant edge intersections despite subpixel normal
separation. Compare overlap strips against the neighbor's buffered context instead.
Run outside measured performance jobs. All source archives are read-only.
"""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path

import mapbox_vector_tile
from shapely.affinity import translate
from shapely.geometry import box, shape
from shapely.ops import unary_union

from analyze_contours import decode, open_ro
from check_planet_pilot import compare_edges, crossings
from compare import ice_agreement
from compare_global import read_tile


def strip_groups(layer, axis, neighbor, context=False):
    center = 0 if neighbor else 4096
    half, lo, hi = (16, -8, 4104) if context else (8, 0, 4096)
    band = box(center - half, lo, center + half, hi) if axis == 0 else box(lo, center - half, hi, center + half)
    groups = defaultdict(list)
    for feature in (layer or {}).get("features", []):
        geometry = shape(feature["geometry"]).intersection(band)
        if geometry.is_empty or geometry.length == 0:
            continue
        if neighbor:
            geometry = translate(geometry, xoff=4096 if axis == 0 else 0, yoff=4096 if axis == 1 else 0)
        props = feature["properties"]
        groups[props["ele"], props["is_ice"]].append(geometry)
    return {key: unary_union(values) for key, values in groups.items()}


def agreement(core, context, ignore_ice=False):
    if ignore_ice:
        def combine(groups):
            values = defaultdict(list)
            for (ele, _), geometry in groups.items():
                values[ele].append(geometry)
            return {key: unary_union(items) for key, items in values.items()}
        core, context = combine(core), combine(context)
    total = outside = 0.
    for key, geometry in core.items():
        total += geometry.length
        other = context.get(key)
        outside += geometry.length if other is None else geometry.difference(other.buffer(8)).length
    return total, outside


def fractions(values):
    return {"ice_length": values[0], "ice_inside_polygon_fraction": values[1] / values[0] if values[0] else None,
            "land_outside_polygon_fraction": values[3] / values[2] if values[2] else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--regions", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    a = parser.parse_args()
    regions = json.loads(a.regions.read_text())
    results = {}
    with open_ro(a.candidate.resolve().as_posix()) as candidate, open_ro(a.reference.resolve().as_posix()) as reference:
        for name, region in regions.items():
            selected = {tuple(key) for key in region["keys"]}
            layers = {}
            ice_by_zoom = {z: {"candidate": [0.] * 4, "reference_self": [0.] * 4} for z in range(9, 14)}
            for key in sorted(selected):
                blob = read_tile(candidate, *key)
                layers[key] = decode(blob, "contours") if blob else None
                ref_blob = read_tile(reference, *key)
                ref = mapbox_vector_tile.decode(gzip.decompress(ref_blob) if ref_blob[:2] == b"\x1f\x8b" else ref_blob,
                    default_options={"y_coord_down": True}) if ref_blob else {}
                for label, layer in (("candidate", layers[key]), ("reference_self", ref.get("contours"))):
                    values = ice_agreement(ref, layer)
                    old = ice_by_zoom[key[0]][label]
                    ice_by_zoom[key[0]][label] = [x + y for x, y in zip(old, values)]
            edges = []
            for key in sorted(selected):
                z, x, y = key
                for axis, neighbor in ((0, (z, (x + 1) % (1 << z), y)), (1, (z, x, y + 1))):
                    if neighbor not in selected:
                        continue
                    distances, unmatched = compare_edges(crossings(layers[key], axis, 4096), crossings(layers[neighbor], axis, 0))
                    if unmatched == 0 and all(distance <= 8 for distance in distances):
                        continue
                    ac = strip_groups(layers[key], axis, False)
                    bc = strip_groups(layers[neighbor], axis, True)
                    ah = strip_groups(layers[key], axis, False, True)
                    bh = strip_groups(layers[neighbor], axis, True, True)
                    classified = [agreement(ac, bh), agreement(bc, ah)]
                    elevations = [agreement(ac, bh, True), agreement(bc, ah, True)]
                    edges.append({"tile": key, "neighbor": neighbor, "axis": axis,
                        "unmatched_crossings": unmatched, "crossing_max_units": max(distances, default=0),
                        "strip_length_units": sum(value[0] for value in classified),
                        "classified_outside_one_pixel_units": sum(value[1] for value in classified),
                        "elevation_only_outside_one_pixel_units": sum(value[1] for value in elevations)})
            results[name] = {"ice_by_zoom": {z: {label: fractions(values) for label, values in labels.items()}
                                             for z, labels in ice_by_zoom.items()}, "crossing_outlier_edges": edges}
            print(name, "reviewed edges", len(edges), "outside units (classified/elevation)",
                  sum(edge["classified_outside_one_pixel_units"] for edge in edges),
                  sum(edge["elevation_only_outside_one_pixel_units"] for edge in edges), flush=True)
    a.out.write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
