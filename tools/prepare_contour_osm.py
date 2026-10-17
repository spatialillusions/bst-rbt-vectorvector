"""Create a reference-complete glacier/peak PBF with native pyosmium filtering.

Requires osmium and numpy. The input is read-only. The final file is published only
after reference validation; existing outputs are never overwritten.
"""
import argparse
from array import array
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import time

import numpy as np
import osmium


def event(stage, **values):
    print(json.dumps({"stage": stage, "time": time.time(), **values}), flush=True)


def validate(path):
    ids = {k: array("q") for k in "nwr"}
    refs = {k: array("q") for k in "nwr"}
    tags = Counter()
    for obj in osmium.FileProcessor(path):
        kind = obj.type_str()
        if kind not in ids:
            continue
        if ids[kind] and obj.id <= ids[kind][-1]:
            raise ValueError(f"Duplicate or unordered {kind} ID {obj.id}")
        ids[kind].append(obj.id)
        natural = obj.tags.get("natural")
        if natural in ("peak", "volcano", "glacier"):
            tags[f"{kind}/{natural}"] += 1
        if kind == "w":
            refs["n"].extend(n.ref for n in obj.nodes)
        elif kind == "r":
            for member in obj.members:
                refs[member.type].append(member.ref)
    missing = {}
    for kind in "nwr":
        present = np.frombuffer(ids[kind], dtype=np.int64)
        requested = np.frombuffer(refs[kind], dtype=np.int64)
        count = 0
        examples = []
        for start in range(0, len(requested), 500_000):
            chunk = requested[start:start + 500_000]
            indices = np.searchsorted(present, chunk)
            ok = indices < len(present)
            valid = np.zeros(len(chunk), dtype=bool)
            valid[ok] = present[indices[ok]] == chunk[ok]
            count += int((~valid).sum())
            examples.extend(int(x) for x in chunk[~valid][:max(0, 10 - len(examples))])
        missing[kind] = {"references_missing": count, "examples": examples}
    result = {"objects": {k: len(v) for k, v in ids.items()}, "selected_tags": dict(tags),
              "references": {k: len(v) for k, v in refs.items()}, "missing": missing}
    if any(v["references_missing"] for v in missing.values()):
        raise ValueError(f"Incomplete references: {result}")
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--relation-depth", type=int, default=8)
    a = p.parse_args()
    source, output = a.source.resolve(), a.output.resolve()
    if output.exists() or output == source:
        raise ValueError("Use a new output path, different from the source")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.stem + ".preparing.osm.pbf")
    if temporary.exists():
        raise ValueError(f"Incomplete preparation exists; use a fresh output: {temporary}")
    before = source.stat()
    start = time.perf_counter()
    counts = Counter()
    pool = osmium.io.ThreadPool(8)
    writer = osmium.BackReferenceWriter(temporary, ref_src=source, remove_tags=False,
                                        relation_depth=a.relation_depth, thread_pool=pool)
    event("select", source=str(source), bytes=before.st_size)
    last = time.monotonic()
    processor = osmium.FileProcessor(source, thread_pool=pool).with_filter(
        osmium.filter.TagFilter(("natural", "glacier"), ("natural", "peak"), ("natural", "volcano")))
    for obj in processor:
        natural = obj.tags.get("natural")
        if (obj.is_node() and natural in ("peak", "volcano")) or natural == "glacier":
            writer.add(obj)
            counts[f"{obj.type_str()}/{natural}"] += 1
        if time.monotonic() - last > 30:
            event("select-progress", selected=dict(counts))
            last = time.monotonic()
    event("complete-references", selected=dict(counts))
    writer.close()
    event("validate", bytes=temporary.stat().st_size)
    checks = validate(temporary)
    after = source.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError("Input changed during preparation")
    with temporary.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    with temporary.open("r+b") as f:
        os.fsync(f.fileno())
    os.replace(temporary, output)
    result = {"source": str(source), "source_bytes": before.st_size, "source_mtime_ns": before.st_mtime_ns,
              "output": str(output), "output_bytes": output.stat().st_size, "sha256": digest,
              "seconds": time.perf_counter() - start, "relation_depth": a.relation_depth,
              "seeds": dict(counts), "validation": checks}
    output.with_suffix(".json").write_text(json.dumps(result, indent=2))
    event("complete", **result)


if __name__ == "__main__":
    main()
