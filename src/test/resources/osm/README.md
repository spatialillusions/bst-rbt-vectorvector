These tiny PBF files are synthetic regression fixtures, generated with pyosmium 4.3.1.
They contain four axis-aligned squares and no real map data.

- `nested-outer`: an untagged two-level relation chain plus a direct outer way (area 101).
- `nested-inner`: a hole represented by a nested multipolygon with its own hole (ice area 68).
- `cyclic`: a glacier relation referencing a descendant that points back to it.
- `missing-inner`: a glacier whose inner relation does not exist.
- `duplicate-outer`: a complete ring made of two ways, with one fragment repeated.

`cyclic` and `missing-inner` deliberately contain invalid references to verify safe rejection. Java tests read
the fixtures directly and do not require Python or downloaded datasets.
