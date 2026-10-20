# Iceland test data sources

| File | Source | Retrieved | Notes |
|---|---|---|---|
| `iceland-latest.osm.pbf` | https://download.geofabrik.de/europe/iceland-latest.osm.pbf | 2026-10-13 (Last-Modified Mon, 12 Oct 2026 23:26:42 GMT), 64,844,742 bytes | © OpenStreetMap contributors, ODbL 1.0 |
| `mapterhorn-iceland-z0-12.pmtiles` | `pmtiles extract https://download.mapterhorn.com/planet.pmtiles --bbox=-24.8,63.1,-13.2,66.8` (go-pmtiles v1.31.2) | 2026-10-13, Mapterhorn download list version 0.0.13 | 447 MB, webp terrarium, z0–12 |
| `6-27-15.pmtiles` | https://download.mapterhorn.com/6-27-15.pmtiles | 2026-10-13 | z13–14, but data bounds 68.3–68.7 N: **not** Iceland. Only used to test multi-archive routing. |
| `download_urls.json`, `attribution.json` | https://download.mapterhorn.com/ | 2026-10-13 | |
| `rbt-reference-iceland.mbtiles` | subset of `E:\RBT\tileserver-gl\data\RBT.mbtiles` (RBT 2.0.0) | 2026-10-13 | layers contours, glacier_polygon, water_polygon, ocean_polygon only |

## Elevation sources covering Iceland (from Mapterhorn `attribution.json`)

* **ÍslandsDEM útgáfa 1.0**: Natural Science Institute of Iceland, CC BY 4.0
* **Copernicus GLO-30**: DLR e.V. 2010-2014, Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA; COPERNICUS full, free and open license

Suggested mbtiles `attribution`:
`<a href="https://mapterhorn.com/attribution">© Mapterhorn</a> (ÍslandsDEM v1.0 © Natural Science Institute of Iceland CC BY 4.0; Copernicus GLO-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA) <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>`
