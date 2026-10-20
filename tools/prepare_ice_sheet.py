"""Convert the OSM Antarctic ice-sheet ZIP (EPSG:3857) to WGS84 EWKB.

Use --download for the official dataset, or --zip for an existing copy. Keeps
split polygons and rock holes; does not union an entire continent. Never
overwrites an existing output. Python is not needed by the Java CLI afterwards.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import time
import urllib.request
import zipfile

import numpy as np
import shapefile
from shapely import get_num_coordinates, make_valid, set_srid, to_wkb, transform
from shapely.geometry import MultiPolygon, shape


URL = "https://osmdata.openstreetmap.de/download/antarctica-icesheet-polygons-3857.zip"


def project(x, y):
    longitude = np.degrees(x / 6378137.)
    if np.any(~np.isfinite(longitude)) or np.any(np.abs(longitude) > 180.000001):
        raise ValueError("Input is outside the Web Mercator world")
    return np.clip(longitude, -180., 180.), np.degrees(2 * np.arctan(np.exp(y / 6378137.)) - np.pi / 2)


def convert(archive_path):
    polygons = []
    with zipfile.ZipFile(archive_path) as archive:
        paths = [name for name in archive.namelist() if name.lower().endswith(".shp")]
        if len(paths) != 1:
            raise ValueError("Expected one ice-sheet shapefile in the ZIP")
        shp = paths[0]
        base = PurePosixPath(shp)
        projection = archive.read(str(base.with_suffix(".prj"))).decode("utf-8")
        if not ("3857" in projection or "Auxiliary_Sphere" in projection or "Pseudo_Mercator" in projection):
            raise ValueError("Expected the EPSG:3857 Antarctic ice-sheet archive")
        with shapefile.Reader(shp=io.BytesIO(archive.read(shp)),
                              shx=io.BytesIO(archive.read(str(base.with_suffix(".shx"))))) as reader:
            for feature in reader.iterShapes():
                if feature.shapeType == shapefile.NULL:
                    continue
                geometry = transform(shape(feature.__geo_interface__), project, interleaved=False)
                if not geometry.is_valid:
                    geometry = make_valid(geometry, method="structure", keep_collapsed=False)
                if geometry.geom_type not in ("Polygon", "MultiPolygon"):
                    raise ValueError("Non-polygonal ice-sheet record")
                polygons.extend(part for part in getattr(geometry, "geoms", [geometry]) if not part.is_empty)
    if not polygons:
        raise ValueError("No ice-sheet polygons")
    return set_srid(MultiPolygon(polygons), 4326)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--download", action="store_true")
    source.add_argument("--zip", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    a = parser.parse_args()
    if a.out.exists():
        raise ValueError("Use a new WKB output path")
    a.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = a.out.with_suffix(".preparing.wkb")
    if temporary.exists():
        raise ValueError("Incomplete WKB exists; use a fresh output path")
    start = time.perf_counter()
    archive = a.zip
    modified = None
    if a.download:
        archive = a.out.with_suffix(".source.zip")
        partial = archive.with_suffix(".downloading.zip")
        if archive.exists() or partial.exists():
            raise ValueError("Archive exists; use --zip to reuse it, or choose a new output path")
        with urllib.request.urlopen(URL, timeout=120) as response, partial.open("xb") as output:
            modified = response.headers.get("Last-Modified")
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        partial.replace(archive)
    geometry = convert(archive)
    data = to_wkb(geometry, include_srid=True)
    with temporary.open("xb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(a.out)
    with archive.open("rb") as source_file:
        source_hash = hashlib.file_digest(source_file, "sha256").hexdigest()
    result = {"source_url": URL if a.download else None, "source_archive": str(archive.resolve()),
        "source_sha256": source_hash, "source_last_modified": modified,
        "license": "ODbL", "attribution": "OpenStreetMap contributors",
        "source_info": "https://osmdata.openstreetmap.de/data/icesheet.html",
        "output": str(a.out.resolve()), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
        "srid": 4326, "polygons": len(geometry.geoms), "coordinates": int(get_num_coordinates(geometry)),
        "bounds": list(geometry.bounds), "seconds": time.perf_counter() - start}
    a.out.with_suffix(".json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
