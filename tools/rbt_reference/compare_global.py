"""Prepare bounded global RBT samples, then compare a generated candidate.

python tools/rbt_reference/compare_global.py prepare --reference /path/to/optional-reference.mbtiles
python tools/rbt_reference/compare_global.py compare --candidate out/rbt-global/candidate/contours.mbtiles

Requires the same numpy/shapely/mapbox-vector-tile dependencies as compare.py;
rendering also requires matplotlib and Pillow. The original archive is read-only.
Geometry checks deliberately ignore glacier classification: generate independent
regional OSM extracts before using these samples to validate is_ice.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3

from analyze_contours import analyze_zoom, decode, expected_index, open_ro
from compare import positional_agreement, render
from extract_reference import filter_layers, lonlat_to_tile


REGIONS = {
    "alps": (7.7, 46.0),
    "himalayas": (86.9, 28.0),
    "andes": (-72.9, -49.3),
    "new-zealand": (171.5, -43.5),
    "greenland": (-42.0, 65.5),
    "netherlands": (5.1, 52.1),
}
LAYERS = {b"contours", b"glacier_polygon"}


def region_keys(lon, lat):
    for z in range(9, 14):
        cx, cy = lonlat_to_tile(lon, lat, z)
        for x in range(cx - 1, cx + 2):
            for y in range(cy - 1, cy + 2):
                yield z, x, y


def read_tile(conn, z, x, y):
    row = conn.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                       (z, x, (1 << z) - 1 - y)).fetchone()
    return row[0] if row else None


def validate_candidate(conn, manifest):
    violations = []
    features = 0
    for z, x, y in sorted({tuple(key) for region in manifest["regions"].values() for key in region["keys"]}):
        blob = read_tile(conn, z, x, y)
        layer = decode(blob, "contours") if blob else None
        if not layer or not layer["features"]:
            continue
        if layer["extent"] != 4096:
            violations.append([z, x, y, "extent"])
        for feature in layer["features"]:
            features += 1
            p = feature["properties"]
            if set(p) != {"ele", "index", "is_ice"} or any(type(v) is not int for v in p.values()):
                violations.append([z, x, y, "keys/types"])
                continue
            ele = p["ele"]
            valid_level = ele != 0 and (ele % 200 == 0 if z <= 10 else ele % 100 == 0 if z == 11 else ele % 20 == 0 or ele % 50 == 0)
            if not valid_level or p["index"] != expected_index(ele) or p["is_ice"] not in (0, 1):
                violations.append([z, x, y, "attributes", p])
            geometry = feature["geometry"]
            if geometry["type"] not in ("LineString", "MultiLineString"):
                violations.append([z, x, y, "geometry type"])
                continue
            parts = [geometry["coordinates"]] if geometry["type"] == "LineString" else geometry["coordinates"]
            if any(len(part) < 2 or any(not -80 <= value <= 4176 for point in part for value in point) for part in parts):
                violations.append([z, x, y, "geometry bounds/length"])
    return {"features_checked": features, "violations": violations}


def prepare(a):
    root = Path(a.out)
    root.mkdir(parents=True, exist_ok=True)
    manifest = {"reference": str(Path(a.reference).resolve()), "regions": {}}
    all_keys = set()
    with open_ro(Path(a.reference).resolve().as_posix()) as src:
        manifest["metadata"] = {k: v for k, v in src.execute("SELECT name,value FROM metadata")
                                if k in ("name", "version", "production_date", "minzoom", "maxzoom")}
        for name, (lon, lat) in REGIONS.items():
            keys = list(region_keys(lon, lat))
            dest = root / name / "reference.mbtiles"
            dest.parent.mkdir(exist_ok=True)
            # Never silently overwrite an earlier extraction.
            with sqlite3.connect(dest) as dst:
                dst.execute("CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row))")
                dst.execute("CREATE TABLE metadata(name TEXT,value TEXT)")
                dst.executemany("INSERT INTO metadata VALUES (?,?)", manifest["metadata"].items())
                missing = []
                for z, x, y in keys:
                    blob = read_tile(src, z, x, y)
                    if blob is None:
                        missing.append([z, x, y])
                        continue
                    raw = gzip.decompress(blob) if blob[:2] == b"\x1f\x8b" else blob
                    # Copy complete layer protobufs without re-encoding coordinates.
                    dst.execute("INSERT INTO tiles VALUES (?,?,?,?)",
                                (z, x, (1 << z) - 1 - y, gzip.compress(filter_layers(raw, LAYERS), mtime=0)))
            manifest["regions"][name] = {"center": [lon, lat], "keys": keys, "missing_reference_tiles": missing}
            all_keys.update(keys)
            print(name, len(keys), "tiles; missing reference:", len(missing), flush=True)
    (root / "tiles.txt").write_text("".join(f"{z}/{x}/{y}\n" for z, x, y in sorted(all_keys)))
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2))


def compare(a):
    root = Path(a.out)
    manifest = json.loads((root / "manifest.json").read_text())
    with open(a.jar, "rb") as jar:
        jar_hash = hashlib.file_digest(jar, "sha256").hexdigest()
    report = {"manifest": manifest, "candidate": str(Path(a.candidate).resolve()),
              "jar_sha256": jar_hash, "regions": {}}
    images = []
    with open_ro(Path(a.candidate).resolve().as_posix()) as cand:
        report["validation"] = validate_candidate(cand, manifest)
        for name, region in manifest["regions"].items():
            rows = []
            with open_ro((root / name / "reference.mbtiles").resolve().as_posix()) as ref:
                for z in range(9, 14):
                    keys = [(x, y) for zz, x, y in region["keys"] if zz == z]
                    r = analyze_zoom(ref, z, "contours", only=set(keys))
                    c = analyze_zoom(cand, z, "contours", only=set(keys))
                    totals = [0., 0., 0., 0.]
                    ref_only = cand_only = 0
                    for x, y in keys:
                        rb, cb = read_tile(ref, z, x, y), read_tile(cand, z, x, y)
                        rl, cl = decode(rb, "contours") if rb else None, decode(cb, "contours") if cb else None
                        has_r = bool(rl and rl["features"])
                        has_c = bool(cl and cl["features"])
                        ref_only += has_r and not has_c
                        cand_only += has_c and not has_r
                        for offset, first, second in ((0, rl, cl), (2, cl, rl)):
                            length, near = positional_agreement(first, second, 16)
                            totals[offset] += length
                            totals[offset + 1] += near
                    rows.append({"zoom": z, "reference": r, "candidate": c,
                                 "reference_only_contour_tiles": ref_only, "candidate_only_contour_tiles": cand_only,
                                 "candidate_near_reference": totals[1] / totals[0] if totals[0] else None,
                                 "reference_near_candidate": totals[3] / totals[2] if totals[2] else None})
                    print(name, z, "coverage", r["tiles_with_layer"], c["tiles_with_layer"],
                          "position", rows[-1]["candidate_near_reference"], flush=True)
                x, y = lonlat_to_tile(*region["center"], 13)
                rb, cb = read_tile(ref, 13, x, y), read_tile(cand, 13, x, y)
                path = root / name / "z13-center.png"
                render(str(path), {"contours": decode(rb, "contours") if rb else None},
                       decode(cb, "contours") if cb else None, f"{name} z13/{x}/{y}")
                images.append(path)
            report["regions"][name] = rows
    (root / "report.json").write_text(json.dumps(report, indent=2))
    from PIL import Image
    thumbs = []
    for path in images:
        with Image.open(path) as src:
            thumb = src.convert("RGB")
            thumb.thumbnail((1100, 550))
            thumbs.append(thumb)
    width = max(im.width for im in thumbs)
    sheet = Image.new("RGB", (width * 2, max(im.height for im in thumbs) * 3), "white")
    for i, im in enumerate(thumbs):
        sheet.paste(im, ((i % 2) * width, (i // 2) * max(t.height for t in thumbs)))
    sheet.save(root / "overview.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "compare"))
    parser.add_argument("--reference", help="Required for prepare: optional external reference archive")
    parser.add_argument("--candidate", default="out/rbt-global/candidate/contours.mbtiles")
    parser.add_argument("--jar", default="target/vectorvector-cli.jar")
    parser.add_argument("--out", default="out/rbt-global")
    args = parser.parse_args()
    if args.action == "prepare" and not args.reference:
        parser.error("prepare requires an explicit --reference archive")
    (prepare if args.action == "prepare" else compare)(args)
