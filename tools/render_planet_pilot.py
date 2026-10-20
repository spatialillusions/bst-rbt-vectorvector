"""Render the actual comparison style through an installed TileServer GL.

Starts a loopback-only server, writes config/images/logs under --out, and stops
only that server afterwards. Source RBT data, styles and fonts remain read-only.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request

from PIL import Image


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--rbt-root", type=Path, required=True)
    parser.add_argument("--node", type=Path, default=Path("C:/Program Files/nodejs/node.exe"))
    parser.add_argument("--server", type=Path, default=Path.home() / "AppData/Roaming/npm/node_modules/tileserver-gl/src/main.js")
    parser.add_argument("--port", type=int, default=8097)
    a = parser.parse_args()
    a.out.mkdir(parents=True, exist_ok=False)
    root, rbt, candidate = Path.cwd().as_posix(), a.rbt_root.resolve(), a.candidate.resolve()
    # TileServer's sprite loader joins the style folder to its root, including an
    # absolute drive letter. Keep a small local copy of the reference style/assets.
    reference_style = a.out / "reference-style"
    reference_style.mkdir()
    for source in (rbt / "styles/RBT-TOPO").iterdir():
        if source.name == "style.json" or source.name.startswith("sprite"):
            shutil.copy2(source, reference_style / source.name)
    config = {"options": {"paths": {"root": root, "styles": root, "mbtiles": root,
                "fonts": (rbt / "fonts").as_posix()}, "serveAllFonts": True, "maxScaleFactor": 1,
                "minRendererPoolSizes": [1], "maxRendererPoolSizes": [1]},
        "data": {"RBT": {"mbtiles": (rbt / "data/RBT.mbtiles").as_posix()},
                 "TERRAIN": {"mbtiles": (rbt / "data/TERRAIN.mbtiles").as_posix(), "encoding": "mapbox", "tileSize": 256},
                 "CONTOURS": {"mbtiles": candidate.as_posix()}},
        "styles": {"candidate": {"style": str(Path("styles/RBT-TOPO-contours/style.json").resolve()), "serve_rendered": True},
                   "reference": {"style": (reference_style / "style.json").resolve().as_posix(), "serve_rendered": True}}}
    config_path = a.out / "config.json"
    config_path.write_text(json.dumps(config, indent=2))
    address = f"http://127.0.0.1:{a.port}"
    evidence = []
    with (a.out / "server.log").open("w", encoding="utf-8") as log:
        server = subprocess.Popen([str(a.node), str(a.server), "--config", str(config_path),
                                   "--bind", "127.0.0.1", "--port", str(a.port)], stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(120):
                if server.poll() is not None:
                    raise RuntimeError("TileServer exited; see server.log")
                try:
                    metadata = json.loads(fetch(address + "/data/CONTOURS.json"))
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(.5)
            else:
                raise TimeoutError("TileServer startup timed out")
            (a.out / "served-metadata.json").write_text(json.dumps(metadata, indent=2))
            for region, lon, lat in (("iceland", -16.7, 64.1), ("alps", 7.7, 46.),
                                     ("matterhorn", 7.6586, 45.9763),
                                     ("greenland", -42., 65.5), ("netherlands", 5.1, 52.1)):
                images = []
                for style in ("candidate", "reference"):
                    url = f"{address}/styles/{style}/static/{lon},{lat},13/768x640.png"
                    path = a.out / f"{region}-{style}.png"
                    path.write_bytes(fetch(url))
                    with Image.open(path) as image:
                        image.load()
                        assert image.size == (768, 640)
                        assert any(lo != hi for lo, hi in image.convert("RGB").getextrema()), "Blank rendering"
                        images.append(image.convert("RGB"))
                    evidence.append({"region": region, "style": style, "url": url, "bytes": path.stat().st_size})
                    print(path, path.stat().st_size, flush=True)
                combined = Image.new("RGB", (1536, 640))
                combined.paste(images[0], (0, 0))
                combined.paste(images[1], (768, 0))
                combined.save(a.out / f"{region}-comparison.png")
            (a.out / "result.json").write_text(json.dumps(evidence, indent=2))
        finally:
            if server.poll() is None:
                server.terminate()
            server.wait(timeout=30)


if __name__ == "__main__":
    main()
