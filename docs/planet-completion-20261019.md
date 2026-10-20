# Completed planet validation

The planet finished on **19 October 2026 at 22:54:49 Europe/Stockholm**
(20:54:49 UTC). Completion was inspected before the local code cutover on
20 October. Frozen deployment: `F:/vectorvector-planet-native-20261019`.

| Check | Result |
|---|---|
| Final supervisor state | `planet-complete-and-verified` |
| Shards | 4,096 complete |
| Stored tiles verified against shard payloads | 23,455,012 |
| SQLite integrity | `ok` |
| Independent pilot positions | 1,060 checked, zero differences |
| Final MBTiles size, including peaks and database overhead | 52,708,900,864 bytes (49.09 GiB) |
| Compressed tile payload bytes | 47,851,889,733 |
| Contour zooms | 9-13 |
| Minimum detail | Existing 20 m / 50 m union at z12-13; no 10 m/z14 extension |

The final file is `tiles/contours.mbtiles` under that deployment. Its size is
about 6.3% below our earlier 52.39 GiB whole-output sample estimate. The RBT
contour-only estimate remains a sampled comparison, not a full RBT extraction;
see [size analysis](contour-output-size.md).

## Identity and recovery

Frozen producer SHA-256:
`a63912b42ac455699a0f958abf62a1a7ab337f3424999cbe2761156bcfe455f2`.
Native build fingerprint:
`419ac1576549c480be2f7423a74e8f066611aa5148e607743d03381caca28a21`.

The guarded import preserved 3,857 completed shards from the repaired producer,
checking each archive's integrity and copied bytes before publishing the new
fingerprint. Native generation reused those shards and completed the remaining
239. This is a verified mixed-producer continuation with byte-equality regression
evidence, not a planet generated entirely with the native decoder.

Three 45-minute sessions recycled as planned, followed by successful generation
and merge in session four. None stopped at the memory or disk floor. Peak RSS
across native generation sessions was 3,092.5 MiB; their minimum available system
RAM was 9,574 MiB. These observations cover the remaining planet workload,
not a guarantee of a multi-day memory plateau over every terrain type.

The subsequent whole-archive verification took 688 seconds and passed. Its own
peak RSS was 542 MiB, but available system RAM fell to 1,439 MiB as Windows cache
grew. It retained the 1,024 MiB stop floor and completed without a floor stop.
This low headroom is a remaining verification/cache limitation; generation's
healthy RAM figures must not be generalized to this stage.

## Evidence and limits

Local evidence: `status.json`, `full-verification.json`,
`quality-sample-verification.json`, `shard-import-report.json` and
`monitor/*/result.json` in the frozen deployment. The verifier compared every
stored key/payload in merger copy order with two database connections maximum.
Its `physical_copy_sha256` is a payload-stream digest, not the SHA-256 of the
entire MBTiles file. Runtime/input identity preflight also passed at cutover.

This establishes successful completion, merge integrity, preserved tile bytes and
pilot equality. It does not establish terrain accuracy or visual quality for
every global area. Previous representative rendering/quality evidence and known
source differences remain in the regional reports. No new planet-wide visual
survey or remote GitHub CI result is claimed.
