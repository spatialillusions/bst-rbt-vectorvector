"""Writes a tile list (z/x/y per line) sampled from the reference mbtiles, for fast tuning runs.

Usage: python make_tile_list.py <reference.mbtiles> <out.txt> [--per-zoom 150] [--minzoom 9]
Tiles without a contours layer are skipped. All z13 tiles are kept (they are the sample windows).
"""
import argparse
import random
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_contours import decode, iter_tiles, open_ro  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reference")
    ap.add_argument("out")
    ap.add_argument("--per-zoom", type=int, default=150)
    ap.add_argument("--minzoom", type=int, default=9)
    a = ap.parse_args()
    conn = open_ro(a.reference)
    rng = random.Random(11)
    tiles = []
    for z in [r[0] for r in conn.execute("SELECT DISTINCT zoom_level FROM tiles ORDER BY 1")]:
        if z < a.minzoom:
            continue
        with_contours = [(x, y) for x, y, d in iter_tiles(conn, z) if decode(d, "contours")]
        if z < 13 and len(with_contours) > a.per_zoom:
            with_contours = rng.sample(with_contours, a.per_zoom)
        tiles += ["%d/%d/%d" % (z, x, y) for x, y in sorted(with_contours)]
        print("z%d: %d tiles" % (z, len(with_contours)))
    with open(a.out, "w") as f:
        f.write("\n".join(tiles) + "\n")


if __name__ == "__main__":
    main()
