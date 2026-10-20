"""Compare MBTiles payloads in key order with bounded memory (Python standard library only)."""
import argparse
import gzip
import sqlite3
from collections import Counter
from pathlib import Path


def rows(connection):
    return connection.execute(
        "SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles "
        "ORDER BY zoom_level,tile_column,tile_row"
    )


def payload(data):
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def compare(a, b, raw=False):
    counts = Counter()
    zooms = Counter()
    left, right = rows(a), rows(b)
    x, y = next(left, None), next(right, None)
    while x is not None or y is not None:
        if y is None or (x is not None and x[:3] < y[:3]):
            counts["only_a"] += 1
            x = next(left, None)
        elif x is None or y[:3] < x[:3]:
            counts["only_b"] += 1
            y = next(right, None)
        else:
            counts["compared"] += 1
            zooms[x[0]] += 1
            left_data = x[3] if raw else payload(x[3])
            right_data = y[3] if raw else payload(y[3])
            if left_data != right_data:
                counts["different"] += 1
                if counts["different"] <= 10:
                    print(f"Different XYZ tile: {x[0]}/{x[1]}/{(1 << x[0]) - 1 - x[2]}")
            x, y = next(left, None), next(right, None)
    print(" ".join(f"{key}={counts[key]}" for key in ("compared", "only_a", "only_b", "different")))
    print("Compared by zoom:", dict(sorted(zooms.items())))
    return int(any(counts[key] for key in ("only_a", "only_b", "different")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("a", type=Path)
    parser.add_argument("b", type=Path)
    parser.add_argument("--raw", action="store_true", help="Compare compressed payload bytes too, including gzip headers")
    args = parser.parse_args()
    with sqlite3.connect(args.a.resolve().as_uri() + "?mode=ro", uri=True) as a, \
            sqlite3.connect(args.b.resolve().as_uri() + "?mode=ro", uri=True) as b:
        return compare(a, b, raw=args.raw)


if __name__ == "__main__":
    raise SystemExit(main())
