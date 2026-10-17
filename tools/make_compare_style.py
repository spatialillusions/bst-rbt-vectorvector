"""Builds styles/RBT-TOPO-contours/style.json from the RBT-TOPO style.

* The four `elevationContour--*` layers read from source CONTOURS (our contours.mbtiles).
* `rbt--elevationContour--*` copies keep reading RBT, in magenta, hidden by default (toggle to overlay both).
* A `peaks` layer (circle + label) reads CONTOURS/peaks.

Usage:
    python tools/make_compare_style.py [--style <RBT-TOPO style.json>] [--sprites <sprite directory>]

The RBT-TOPO style is read from the supplied tileserver root, or downloaded from
github.com/ReleasableBasemapTiles/RBT-Public when that is absent. Sprites are copied next to the style.
"""
import argparse
import copy
import glob
import json
import os
import shutil
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "styles", "RBT-TOPO-contours")
URL = "https://raw.githubusercontent.com/ReleasableBasemapTiles/RBT-Public/main/current-release/styles/RBT-TOPO/style.json"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rbt-root", default=os.environ.get("RBT_TILESERVER_ROOT", "H:/RBT/tileserver-gl"))
    ap.add_argument("--style")
    ap.add_argument("--sprites")
    a = ap.parse_args()
    local = os.path.join(a.rbt_root, "styles", "RBT-TOPO")
    a.style = a.style or os.path.join(local, "style.json")
    a.sprites = a.sprites or local
    if os.path.exists(a.style):
        with open(a.style, encoding="utf-8") as f:
            style = json.load(f)
    else:
        with urllib.request.urlopen(URL) as r:
            style = json.loads(r.read().decode("utf-8"))

    style["name"] = "RBT-TOPO with vectorvector contours"
    style["sources"]["CONTOURS"] = {"type": "vector", "url": "mbtiles://{CONTOURS}"}
    layers = []
    rbt_copies = []
    for layer in style["layers"]:
        if layer.get("source-layer") == "contours" and layer.get("source") == "RBT":
            ours = copy.deepcopy(layer)
            ours["source"] = "CONTOURS"
            layers.append(ours)
            theirs = copy.deepcopy(layer)
            theirs["id"] = "rbt--" + layer["id"]
            theirs.setdefault("layout", {})["visibility"] = "none"
            paint = theirs.setdefault("paint", {})
            if theirs["type"] == "line":
                paint["line-color"] = "#ff00ff"
            else:
                paint["text-color"] = "#ff00ff"
            rbt_copies.append(theirs)
        else:
            layers.append(layer)
    layers.extend(rbt_copies)
    layers.append({
        "id": "peaks--point", "type": "circle", "source": "CONTOURS", "source-layer": "peaks", "minzoom": 9,
        "filter": ["step", ["zoom"], ["<=", ["get", "rank"], 5], 11, ["<=", ["get", "rank"], 15], 12, True],
        "paint": {"circle-radius": 2.5, "circle-color": "#5b3a29", "circle-stroke-color": "#ffffff", "circle-stroke-width": 1}
    })
    layers.append({
        "id": "peaks--label", "type": "symbol", "source": "CONTOURS", "source-layer": "peaks", "minzoom": 9,
        "filter": ["all", ["has", "name"], ["step", ["zoom"], ["<=", ["get", "rank"], 5], 11, ["<=", ["get", "rank"], 15], 12, True]],
        "layout": {
            "text-field": ["case", ["has", "ele"], ["concat", ["get", "name"], "\n", ["to-string", ["get", "ele"]], " m"], ["get", "name"]],
            "text-font": ["NotoSansItalic"], "text-size": 11, "text-offset": [0, 0.6], "text-anchor": "top",
            "text-max-width": 8
        },
        "paint": {"text-color": "#5b3a29", "text-halo-color": "rgba(255,255,255,0.9)", "text-halo-width": 1.2}
    })
    style["layers"] = layers
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "style.json"), "w", encoding="utf-8") as f:
        json.dump(style, f, indent=1, ensure_ascii=False)
    copied = 0
    for sprite in glob.glob(os.path.join(a.sprites, "sprite*")):
        shutil.copy(sprite, OUT)
        copied += 1
    print("wrote %s (%d layers, %d sprite files)" % (os.path.join(OUT, "style.json"), len(layers), copied))


if __name__ == "__main__":
    main()
