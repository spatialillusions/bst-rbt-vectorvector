# Contour analysis: `data/iceland/rbt-reference-iceland.mbtiles`

Layer: `contours`

## Metadata

* `minzoom`: 0
* `maxzoom`: 13
* `bounds`: -180.000000,-85.051129,180.000000,85.051129
* `format`: pbf
* `center`: -77.0365,38.8977,10
* `name`: Releasable Basemap Tiles (RBT)
* `version`: 2.0.0
* `production_date`: 2026-09-07T03:02:05.442000Z
* `description`:
* `attribution`: <a href='https://www.agc.army.mil'>Army Geospatial Center</a> <a href='https://www.openstreetmap.org/copyright'>© OpenStreetMap contributors</a> <a href='https://fieldmaps.io'>FieldMaps</a> <a href='h…
* `tags`: ["Army", "Basemap", "Tiles", "Vector", "Schema", "Geospatial", "OpenStreetMap", "FieldMaps", "Natural Earth", "USGS", "Overture Maps", "NGA", "OurAirports"]
* `source`: https://www.agc.army.mil/
* `license`: [{"name": "OpenStreetMap", "license": "ODbL 1.0", "url": "https://www.openstreetmap.org/copyright"}, {"name": "FieldMaps", "license": "ODbL 1.0", "url": "https://fieldmaps.io/data/"}, {"name": "Overtu…
* `creators`: {"creators:name": "Team SACI", "creators:websites": "https://www.strategicaci.com/ & https://www.twelvelabs.io/ & https://www.axismaps.com/"}
* `type`: baselayer
* `rbt_subset`: Iceland bbox (-24.6, 63.2, -13.3, 66.7); z>=13 windows oraefajokull,eyjafjallajokull,esja; layers contours,glacier_polygon,ocean_polygon,water_polygon

`vector_layers` entry:

```json
[
 {
  "id": "contours",
  "description": "",
  "minzoom": 9,
  "maxzoom": 13,
  "fields": {
   "ele": "Number",
   "index": "Number",
   "is_ice": "Number"
  }
 }
]
```

## Schema and intervals per zoom

| z | tiles | keys (types) | ele min..max | #ele | interval (gcd of diffs) | negative | zero | geom types | extent | coord min..max |
|---|---|---|---|---|---|---|---|---|---|---|
| 9 | 123 | ele:int, index:int, is_ice:int | 200..2000 | 10 | 200 | False | False | LineString:5143, MultiLineString:1233 | [4096] | -80..4176 |
| 10 | 433 | ele:int, index:int, is_ice:int | 200..2000 | 10 | 200 | False | False | LineString:5527, MultiLineString:2042 | [4096] | -80..4176 |
| 11 | 1603 | ele:int, index:int, is_ice:int | 100..2000 | 20 | 100 | False | False | LineString:13942, MultiLineString:7195 | [4096] | -80..4176 |
| 12 | 6330 | ele:int, index:int, is_ice:int | -60..2060 | 128 | 10 | True | False | LineString:136439, MultiLineString:76222 | [4096] | -80..4176 |
| 13 | 191 | ele:int, index:int, is_ice:int | 20..2060 | 124 | 10 | False | False | LineString:4557, MultiLineString:3304 | [4096] | -80..4176 |

## `index` and `is_ice`

| z | index histogram | index mismatches (ele->index) | ice/land features | ice length share |
|---|---|---|---|---|
| 9 | 200:3354, 400:1619, 800:1403 | none | 456 / 5920 | 0.058 |
| 10 | 200:4025, 400:1964, 800:1580 | none | 604 / 6965 | 0.058 |
| 11 | 100:10872, 200:5540, 400:2677, 800:2048 | none | 1778 / 19359 | 0.056 |
| 12 | 20:142468, 50:35697, 100:17768, 200:9118, 400:4565, 800:3045 | none | 17902 / 194759 | 0.055 |
| 13 | 20:5227, 50:1310, 100:686, 200:329, 400:179, 800:130 | none | 2532 / 5329 | 0.248 |

## Smoothness (per-tile values; median / p90)

| z | vertex_spacing | turn_angle (deg) | sharp_ratio (>45°) | length_per_tile | features_per_tile |
|---|---|---|---|---|---|
| 9 | 29.2 / 35.3 | 27.4 / 32.6 | 0.130 / 0.197 | 52292.5 / 117294.7 | 49.0 / 93.6 |
| 10 | 38.4 / 50.4 | 19.7 / 24.2 | 0.043 / 0.089 | 28027.3 / 74745.0 | 15.0 / 35.0 |
| 11 | 52.4 / 72.6 | 14.2 / 18.1 | 0.012 / 0.033 | 30176.5 / 88652.1 | 11.0 / 26.0 |
| 12 | 69.5 / 101.7 | 10.6 / 13.7 | 0.003 / 0.013 | 85904.9 / 287676.6 | 26.0 / 69.0 |
| 13 | 92.3 / 117.2 | 7.4 / 9.5 | 0.000 / 0.001 | 132257.7 / 233844.8 | 33.0 / 84.0 |
