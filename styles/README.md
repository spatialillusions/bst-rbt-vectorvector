# Standalone contour style

`contours/style.json` is an original, checked-in MapLibre style. It uses only
`CONTOURS/contours` and optional `CONTOURS/peaks` from our generated MBTiles.
Brown lines show land contours, blue lines show ice contours; index contours are
thicker. Peak points are shown without labels. No RBT archive, terrain archive,
sprites or font files are needed. This is a contour preview, without a basemap.

From the repository root, build and run the example in the main README, then:

```sh
npm install --global tileserver-gl@5.6.0
tileserver-gl --config styles/config.json --bind 127.0.0.1 --port 8080
```

Open http://localhost:8080/styles/contours/ . The configured map starts at the
small committed fixture near Iceland, zoom 10. TileServer GL has native system
dependencies; use its official installation instructions for your platform.
The server's browser viewer needs its normal JavaScript dependencies. The style
itself uses no remote assets. To display another generated archive, edit the
single `data.CONTOURS.mbtiles` path in `config.json` and move the map to its area.
The MBTiles path is relative to the repository root (`mbtiles: ".."` from styles).
For another MapLibre application, replace `mbtiles://{CONTOURS}` with the HTTP
TileJSON URL exposed by your tile server.

## Optional RBT research comparison

`tools/make_compare_style.py --rbt-root <your-tileserver-directory>` generates
an ignored comparison style from your separately supplied RBT-TOPO style/sprites.
It is not the default example and is not needed to build, test, generate or
verify contours. Research rendering via `tools/render_planet_pilot.py` likewise
requires an explicit `--rbt-root`; its configuration is created separately under
its requested output directory. Historical comparison reports describe the
external datasets used at the time; those datasets are not included here.
