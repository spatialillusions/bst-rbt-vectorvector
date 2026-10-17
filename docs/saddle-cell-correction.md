# Saddle-cell correction during migration

The older implementation's saddle-cell regression revealed that the imported reference and
fast marching-squares generators each emitted only one segment in cases 5 and 10. Those cases
have four straddling edge crossings and need two segments. Dropping two crossings can leave
contour ends inside the grid.

Both generators now retain all four crossings and use the same determinant of corner heights
relative to the contour level to select the bilinear pairing. Positive determinant pairs
bottom-right and left-top; negative pairs bottom-left and right-top. An exact zero uses the
first pairing consistently, including under height inversion. The center-average heuristic
is not used. Background: [Nielson and Hamann's original paper](https://escholarship.org/uc/item/17p025zk).

Four focused tests failed before the correction. Five focused tests now cover every one of the
16 cell cases, both saddle orientations, determinant connectivity including a case where the
center average gives the wrong choice, height inversion and the absence of interior dangling
ends in a checkerboard. They exercise both generators. The complete source-only suite reports
152 tests: 148 passed, four skipped, zero failures/errors. All four existing default-profile
upstream golden hashes remain unchanged on that fixed smooth terrain.

This intentionally changes geometry for inputs containing saddle cells; byte-for-byte upstream
compatibility for arbitrary terrain is not claimed. The earlier F: pilot JAR and its artifacts
remain intact. The migrated executable needs fresh quality/storage/recovery validation and a
new planet fingerprint. See [the migration report](migration-report.md) for current gate status.
