# Comparison style

`RBT-TOPO-contours/style.json` is RBT-TOPO with its four `elevationContour--*` layers reading our
`contours.mbtiles` (source `CONTOURS`). The original RBT contour layers are kept as `rbt--elevationContour--*`
in magenta and hidden by default; toggle their visibility to overlay both. A `peaks` circle + label layer
reads `CONTOURS/peaks`.

Regenerate it (and copy the sprites) with:

```
python tools/make_compare_style.py
```

`style.json` and the sprites are generated, not committed.
The default RBT tileserver root is `H:/RBT/tileserver-gl`; override it with `--rbt-root <directory>`
or the `RBT_TILESERVER_ROOT` environment variable. `--style` and `--sprites` can also be supplied
individually. If the style is absent locally, the script retrieves the upstream RBT style.

## Running tileserver-gl

`config.json` reads `RBT.mbtiles`, `TERRAIN.mbtiles` and the fonts from `H:\RBT\tileserver-gl` (read-only) through
two directory junctions inside `out/`, because tileserver-gl cannot join absolute paths on another drive. Our tiles
come from `out/contours/contours.mbtiles`. Create the junctions once from the project root;
only the junction entries inside this project's `out/` directory are created:

```powershell
New-Item -ItemType Directory -Force out
New-Item -ItemType Junction -Path out/rbt-data -Target H:/RBT/tileserver-gl/data
New-Item -ItemType Junction -Path out/rbt-fonts -Target H:/RBT/tileserver-gl/fonts
```

Then:

```
cd styles
tileserver-gl --config config.json --port 8090
```

Then open http://localhost:8090/styles/RBT-TOPO-contours/#10/64.0/-16.7 (Vatnajökull / Öræfajökull).

The fonts `NGATopo_Cn_ita` and `NotoSansItalic` must exist in the configured fonts folder.

Equivalent entries for an existing tileserver-gl `config.json`:

```json
"data": {
  "CONTOURS": { "mbtiles": "contours.mbtiles" }
},
"styles": {
  "RBT-TOPO-contours": { "style": "RBT-TOPO-contours/style.json", "serve_data": true, "serve_rendered": true }
}
```
