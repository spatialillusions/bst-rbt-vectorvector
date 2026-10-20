"""Extract an Iceland subset of the RBT reference mbtiles (read-only).

Usage:
    python extract_reference.py --src /path/to/optional-reference.mbtiles
                                [--out data/iceland/rbt-reference-iceland.mbtiles]
                                [--all-layers]

* z <= 12: every tile in the Iceland bbox.
* z >= 13: three sample windows (8x8 tiles) around Oraefajokull, Eyjafjallajokull and Esja.
* By default only the layers in KEEP_LAYERS are kept. Layer bytes are copied verbatim
  (no decode/re-encode), so geometry and attributes are bit-identical to the source.
"""
import argparse
import gzip
import math
import os
import sqlite3

BBOX = (-24.6, 63.2, -13.3, 66.7)  # minLon, minLat, maxLon, maxLat
KEEP_LAYERS = {b"contours", b"glacier_polygon", b"water_polygon", b"ocean_polygon"}
SAMPLES = {
    "oraefajokull": (63.99, -16.67),
    "eyjafjallajokull": (63.63, -19.62),
    "esja": (64.25, -21.65),
}
WINDOW = 8


def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_r)) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def bbox_range(z, bbox=BBOX):
    x0, y0 = lonlat_to_tile(bbox[0], bbox[3], z)
    x1, y1 = lonlat_to_tile(bbox[2], bbox[1], z)
    return x0, x1, y0, y1


def sample_ranges(z):
    for name, (lat, lon) in SAMPLES.items():
        cx, cy = lonlat_to_tile(lon, lat, z)
        h = WINDOW // 2
        yield name, (cx - h, cx + h - 1, cy - h, cy + h - 1)


# --- minimal protobuf helpers (vector_tile.Tile: field 3 = repeated Layer; Layer field 1 = name) ---

def read_varint(buf, i):
    result = shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def write_varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def layer_name(layer):
    i = 0
    while i < len(layer):
        key, i = read_varint(layer, i)
        field, wt = key >> 3, key & 7
        if wt == 2:
            ln, i = read_varint(layer, i)
            if field == 1:
                return bytes(layer[i:i + ln])
            i += ln
        elif wt == 0:
            _, i = read_varint(layer, i)
        elif wt == 5:
            i += 4
        elif wt == 1:
            i += 8
        else:
            raise ValueError("unsupported wire type %d" % wt)
    return None


def filter_layers(tile, keep):
    out = bytearray()
    i = 0
    while i < len(tile):
        start = i
        key, i = read_varint(tile, i)
        if key & 7 != 2:
            raise ValueError("unexpected wire type in Tile")
        ln, i = read_varint(tile, i)
        payload = tile[i:i + ln]
        i += ln
        if (key >> 3) == 3 and layer_name(payload) in keep:
            out += tile[start:i]
    return bytes(out)


def process_tile(data, keep):
    if keep is None:
        return data
    gz = data[:2] == b"\x1f\x8b"
    raw = gzip.decompress(data) if gz else data
    filtered = filter_layers(raw, keep)
    if not filtered:
        return None
    return gzip.compress(filtered, mtime=0) if gz else filtered


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", default="data/iceland/rbt-reference-iceland.mbtiles")
    ap.add_argument("--all-layers", action="store_true")
    ap.add_argument("--minzoom", type=int, default=0)
    ap.add_argument("--maxzoom", type=int, default=None)
    args = ap.parse_args()
    keep = None if args.all_layers else KEEP_LAYERS

    src = sqlite3.connect("file:%s?mode=ro" % args.src, uri=True)
    meta = dict(src.execute("SELECT name, value FROM metadata").fetchall())
    maxzoom = args.maxzoom if args.maxzoom is not None else int(meta["maxzoom"])

    if os.path.exists(args.out):
        os.remove(args.out)
    dst = sqlite3.connect(args.out)
    dst.execute("CREATE TABLE metadata (name text, value text)")
    dst.execute("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
    dst.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    for k, v in meta.items():
        dst.execute("INSERT INTO metadata VALUES (?, ?)", (k, v))
    dst.execute("INSERT INTO metadata VALUES ('rbt_subset', ?)",
                ("Iceland bbox %s; z>=13 windows %s; layers %s" % (
                    BBOX, ",".join(SAMPLES), "all" if keep is None else ",".join(sorted(l.decode() for l in keep))),))

    q = ("SELECT tile_column, tile_row, tile_data FROM tiles WHERE zoom_level=? "
         "AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?")
    for z in range(args.minzoom, maxzoom + 1):
        ranges = [("bbox", bbox_range(z))] if z <= 12 else list(sample_ranges(z))
        count = size = 0
        for name, (x0, x1, y0, y1) in ranges:
            n = 2 ** z
            r0, r1 = n - 1 - y1, n - 1 - y0  # TMS rows
            for col, row, data in src.execute(q, (z, x0, x1, r0, r1)):
                data = process_tile(data, keep)
                if data is None:
                    continue
                dst.execute("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", (z, col, row, data))
                count += 1
                size += len(data)
            print("z%d %s x=%d..%d y=%d..%d" % (z, name, x0, x1, y0, y1))
        dst.commit()
        print("z%d: %d tiles, %.1f MB" % (z, count, size / 1e6), flush=True)
    dst.close()


if __name__ == "__main__":
    main()
