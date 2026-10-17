"""Reproducible real-terrain CLI benchmarks. Windows telemetry uses only the standard library.

Fresh JVMs, fixed tile lists, serialized runs, per-run logs/GC logs and optional JFR.
Reports startup separately from tile processing; comparisons use raw compressed payload hashes.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import sqlite3
import subprocess
import threading
import time


class Memory(C.Structure):
    _fields_ = [("cb", W.DWORD), ("faults", W.DWORD)] + [
        (name, C.c_size_t) for name in
        ("peak_rss", "rss", "peak_paged", "paged", "peak_nonpaged", "nonpaged", "pagefile", "peak_pagefile", "private")
    ]


class IO(C.Structure):
    _fields_ = [(x, C.c_ulonglong) for x in ("reads", "writes", "other", "read_bytes", "write_bytes", "other_bytes")]


kernel = C.WinDLL("kernel32", use_last_error=True)
kernel.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
kernel.OpenProcess.restype = W.HANDLE
kernel.GetProcessTimes.argtypes = [W.HANDLE] + [C.POINTER(W.FILETIME)] * 4
kernel.GetProcessIoCounters.argtypes = [W.HANDLE, C.POINTER(IO)]
kernel.K32GetProcessMemoryInfo.argtypes = [W.HANDLE, C.POINTER(Memory), W.DWORD]
kernel.CloseHandle.argtypes = [W.HANDLE]


def snapshot(handle):
    times = [W.FILETIME() for _ in range(4)]
    if not kernel.GetProcessTimes(handle, *(C.byref(x) for x in times)):
        raise C.WinError(C.get_last_error())
    cpu = sum((x.dwHighDateTime << 32) + x.dwLowDateTime for x in times[2:]) / 1e7
    memory, io = Memory(), IO()
    memory.cb = C.sizeof(memory)
    kernel.K32GetProcessMemoryInfo(handle, C.byref(memory), C.sizeof(memory))
    kernel.GetProcessIoCounters(handle, C.byref(io))
    return {"t": time.perf_counter(), "cpu_s": cpu, "rss": memory.rss, "peak_rss": memory.peak_rss,
            "private": memory.private, "read_bytes": io.read_bytes, "write_bytes": io.write_bytes}


class DiskCounters:
    """Whole-machine physical I/O, including unrelated applications; unavailable counters are omitted."""
    def __init__(self):
        self.dll = C.WinDLL("pdh")
        self.query = W.HANDLE()
        self.counters = {}
        self.dll.PdhOpenQueryW(None, 0, C.byref(self.query))
        for name, path in {"disk_read_bps": r"\PhysicalDisk(_Total)\Disk Read Bytes/sec",
                           "disk_write_bps": r"\PhysicalDisk(_Total)\Disk Write Bytes/sec",
                           "disk_queue": r"\PhysicalDisk(_Total)\Avg. Disk Queue Length",
                           "system_cpu_pct": r"\Processor(_Total)\% Processor Time",
                           "cpu_perf_pct": r"\Processor Information(_Total)\% Processor Performance",
                           "cpu_mhz": r"\Processor Information(_Total)\Processor Frequency",
                           "system_cache_resident_bytes": r"\Memory\System Cache Resident Bytes",
                           "system_cache_bytes": r"\Memory\Cache Bytes",
                           "system_committed_bytes": r"\Memory\Committed Bytes",
                           "system_commit_limit_bytes": r"\Memory\Commit Limit",
                           "pool_nonpaged_bytes": r"\Memory\Pool Nonpaged Bytes",
                           "pool_paged_bytes": r"\Memory\Pool Paged Bytes",
                           "modified_page_list_bytes": r"\Memory\Modified Page List Bytes",
                           "standby_normal_bytes": r"\Memory\Standby Cache Normal Priority Bytes",
                           "pages_input_per_sec": r"\Memory\Pages Input/sec"}.items():
            counter = W.HANDLE()
            if self.dll.PdhAddEnglishCounterW(self.query, path, 0, C.byref(counter)) == 0:
                self.counters[name] = counter
        self.dll.PdhCollectQueryData(self.query)

    def sample(self):
        class Value(C.Structure):
            _fields_ = [("status", W.DWORD), ("value", C.c_double)]
        self.dll.PdhCollectQueryData(self.query)
        result = {}
        for name, counter in self.counters.items():
            value = Value()
            if self.dll.PdhGetFormattedCounterValue(counter, 0x200, None, C.byref(value)) == 0 and value.status in (0, 1):
                result[name] = value.value
        return result

    def close(self):
        self.dll.PdhCloseQuery(self.query)


def wait_for_explorer(seconds):
    """Wait for a sustained quiet interval, without pausing or changing the user's transfers."""
    ids = json.loads(subprocess.check_output([
        "powershell", "-NoProfile", "-Command", "@(Get-Process explorer).Id | ConvertTo-Json -Compress"
    ], creationflags=subprocess.CREATE_NO_WINDOW, text=True))
    if isinstance(ids, int):
        ids = [ids]
    handles = [kernel.OpenProcess(0x1000 | 0x0010, False, pid) for pid in ids]
    handles = [h for h in handles if h]
    disks = DiskCounters()
    quiet = time.perf_counter()
    last_print = 0
    try:
        before = sum(snapshot(h)[key] for h in handles for key in ("read_bytes", "write_bytes"))
        previous = time.perf_counter()
        while time.perf_counter() - quiet < seconds:
            time.sleep(5)
            now = time.perf_counter()
            current = sum(snapshot(h)[key] for h in handles for key in ("read_bytes", "write_bytes"))
            rate = (current - before) / (now - previous) / 2**20
            disk = disks.sample()
            physical = (disk.get("disk_read_bps", 0) + disk.get("disk_write_bps", 0)) / 2**20
            if rate > 1 or physical > 20:
                quiet = now
            if now - last_print >= 30:
                print(f"Waiting for transfer: Explorer {rate:.1f}, physical disks {physical:.1f} MiB/s combined; "
                      f"quiet for {now - quiet:.0f}/{seconds}s", flush=True)
                last_print = now
            before, previous = current, now
        print(f"Explorer quiet for {seconds}s; starting measurements", flush=True)
    finally:
        disks.close()
        for handle in handles:
            kernel.CloseHandle(handle)


def tile_xy(lon, lat, z):
    n = 1 << z
    return int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)


def workload(case, width):
    if case == "shard":
        # A complete default production shard (z6/29/16, eastern Iceland and adjacent sea).
        tiles = [(z, x, y) for z in range(9, 14) for x in range(29 * (1 << (z - 6)), 30 * (1 << (z - 6)))
                 for y in range(16 * (1 << (z - 6)), 17 * (1 << (z - 6)))]
        return tiles, [("iceland_default_shard", "6/29/16")]
    centers = {"mixed": [("iceland", -16.7, 64.1), ("alps", 7.7, 46.0),
                          ("himalaya", 86.9, 28.0), ("netherlands", 5.1, 52.1)],
               "polar": [("greenland", -42.0, 65.5), ("antarctica", -65.0, -66.5)],
               "fresh": [("andes", -72.9, -49.3), ("new_zealand", 171.5, -43.5)],
               "ocean": [("pacific", -145.0, 0.0)],
               "iceland": [("iceland", -16.7, 64.1)]}
    tiles = set()
    for _, lon, lat in centers[case]:
        cx, cy = tile_xy(lon, lat, 12)
        for x in range(cx - width // 2, cx - width // 2 + width):
            for y in range(cy - width // 2, cy - width // 2 + width):
                tiles.add((12, x, y))
                for dx in (0, 1):
                    for dy in (0, 1):
                        tiles.add((13, x * 2 + dx, y * 2 + dy))
    return sorted(tiles), centers[case]


def payload_summary(path):
    digest = hashlib.sha256()
    with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True) as connection:
        count = 0
        size = 0
        for z, x, y, data in connection.execute("SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles ORDER BY 1,2,3"):
            digest.update(f"{z}/{x}/{y}:{len(data)}:".encode())
            digest.update(data)
            count += 1
            size += len(data)
    return {"output_tiles": count, "payload_bytes": size, "payload_sha256": digest.hexdigest()}


def run(args, workers, repeat, tile_list, tiles):
    name = f"{args.label}-{args.case}-w{workers}-c{args.cache}-{args.gc}-r{repeat}"
    folder = args.out / name
    folder.mkdir(parents=True, exist_ok=False)
    command = [str(args.java), "-Xms1g", f"-Xmx{args.heap}", "-XX:+ExitOnOutOfMemoryError",
               "-XX:+UseG1GC" if args.gc == "g1" else "-XX:+UseParallelGC",
               "-Xlog:gc*:file=gc.log:time,uptime,level,tags"]
    command += args.jvm_arg
    if args.jfr:
        command += ["-XX:StartFlightRecording=filename=profile.jfr,settings=profile,dumponexit=true"]
    command += ["-jar", str(args.jar), "--profile", "rbt", "--dataFormat", "mapterhorn",
                "--pmtiles", str(args.elevation), "--tileList", str(tile_list), "-minZ", "6", "-maxZ", "13",
                "-minX", "0", "-maxX", "63", "-minY", "0", "-maxY", "63",
                "--parallelism", str(workers), "--elevationCacheTiles", str(args.cache),
                "--glacierCacheTiles", str(args.glacier_cache), "-f", "mbtiles", "-o", str(folder / "tiles")]
    if args.osm:
        command += ["--glaciers", str(args.osm), "--peaks"]
    if args.resumable:
        command += ["--resumableShards"]
    (folder / "command.json").write_text(json.dumps(command, indent=2))
    start = time.perf_counter()
    process = subprocess.Popen(command, cwd=folder, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace", bufsize=1,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    handle = kernel.OpenProcess(0x1000 | 0x0010, False, process.pid)
    if not handle:
        process.terminate()
        raise C.WinError(C.get_last_error())
    events = []

    def read_output():
        with (folder / "run.log").open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if "Processing area of" in line:
                    events.append(("start", snapshot(handle)))
                elif "Completed in" in line:
                    events.append(("end", snapshot(handle)))

    reader = threading.Thread(target=read_output)
    reader.start()
    samples = []
    disks = DiskCounters()
    try:
        while process.poll() is None:
            sample = snapshot(handle)
            sample.update(disks.sample())
            samples.append(sample)
            time.sleep(0.25)
        final = snapshot(handle)
        reader.join()
    finally:
        disks.close()
        kernel.CloseHandle(handle)
    elapsed = time.perf_counter() - start
    if process.returncode:
        raise RuntimeError(f"{name}: CLI exit={process.returncode}; see {folder / 'run.log'}")
    starts = [s for kind, s in events if kind == "start"]
    ends = [s for kind, s in events if kind == "end"]
    if len(starts) != 1 or len(ends) != 1:
        raise RuntimeError(f"Missing timing markers: {events}")
    begin, end = starts[0], ends[0]
    seconds = end["t"] - begin["t"]
    during = [s for s in samples if begin["t"] <= s["t"] <= end["t"]]
    gc_log = (folder / "gc.log").read_text()
    pauses = [float(x) for x in re.findall(r"Pause[^\n]*? ([0-9.]+)ms", gc_log)]
    result = {"name": name, "case": args.case, "workers": workers, "cache": args.cache, "heap": args.heap,
              "glacier_cache": args.glacier_cache,
              "gc": args.gc, "jfr": args.jfr, "requested_tiles": len(tiles), "wall_s": elapsed,
              "resumable": args.resumable,
              "jvm_args": args.jvm_arg,
              "startup_s": begin["t"] - start, "processing_s": seconds, "tiles_per_s": len(tiles) / seconds,
              "process_cpu_s": final["cpu_s"], "processing_cpu_s": end["cpu_s"] - begin["cpu_s"],
              "processing_cpu_pct": (end["cpu_s"] - begin["cpu_s"]) / seconds / os.cpu_count() * 100,
              "peak_rss_mib": max(s["peak_rss"] for s in samples) / 2**20,
              "peak_private_mib": max(s["private"] for s in samples) / 2**20,
              "finalize_s": elapsed - (end["t"] - start),
              "logical_read_mib": (end["read_bytes"] - begin["read_bytes"]) / 2**20,
              "logical_write_mib": (end["write_bytes"] - begin["write_bytes"]) / 2**20,
              "gc_pause_ms": sum(pauses), "gc_pause_count": len(pauses),
              "jar_sha256": args.jar_hash, "folder": str(folder)}
    for key in ("disk_read_bps", "disk_write_bps", "disk_queue", "system_cpu_pct", "cpu_perf_pct", "cpu_mhz"):
        values = [s[key] for s in during if key in s]
        if values:
            result[f"mean_{key}"] = sum(values) / len(values)
    result.update(payload_summary(folder / "tiles/contours.mbtiles"))
    (folder / "samples.json").write_text(json.dumps(samples))
    (folder / "result.json").write_text(json.dumps(result, indent=2))
    with (args.out / "results.jsonl").open("a") as stream:
        stream.write(json.dumps(result) + "\n")
    print(json.dumps(result), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--jar", type=Path, default=root / "target/vectorvector-cli.jar")
    parser.add_argument("--compare-jar", type=Path, help="Interleave measurements of a second executable")
    parser.add_argument("--java", type=Path, default=next((root / ".tools").glob("jdk-21*/bin/java.exe")))
    parser.add_argument("--elevation", type=Path, default=Path(r"D:\Mapterhorn\mapterhorn.pmtiles"))
    parser.add_argument("--osm", type=Path)
    parser.add_argument("--out", type=Path, default=root / "out/performance")
    parser.add_argument("--label", default="baseline")
    parser.add_argument("--case", choices=("mixed", "ocean", "polar", "iceland", "shard", "fresh"), default="mixed")
    parser.add_argument("--width", type=int, default=8)
    parser.add_argument("--workers", default="1,2,4,6,8,12")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--cache", type=int, default=128)
    parser.add_argument("--glacier-cache", type=int, default=256)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--gc", choices=("g1", "parallel"), default="g1")
    parser.add_argument("--jfr", action="store_true")
    parser.add_argument("--resumable", action="store_true")
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--wait-for-explorer", type=int, default=0,
                        help="Wait this many quiet seconds before starting; no transfer is interrupted")
    args = parser.parse_args()
    for key in ("jar", "compare_jar", "java", "elevation", "out", "osm"):
        if getattr(args, key):
            setattr(args, key, getattr(args, key).resolve())
    args.out.mkdir(parents=True, exist_ok=True)
    if args.wait_for_explorer:
        wait_for_explorer(args.wait_for_explorer)
    tiles, regions = workload(args.case, args.width)
    tile_list = args.out / f"{args.case}-{args.width}.tiles.txt"
    tile_list.write_text("".join(f"{z}/{x}/{y}\n" for z, x, y in tiles))
    (args.out / f"{args.case}-{args.width}.regions.json").write_text(json.dumps(regions))
    versions = [("baseline", args.jar), ("optimized", args.compare_jar)] if args.compare_jar else [("", args.jar)]
    hashes = {}
    for _, jar in versions:
        with jar.open("rb") as file:
            hashes[str(jar)] = hashlib.file_digest(file, "sha256").hexdigest()
    configs = [(w, r, version, jar) for r in range(args.repeats) for w in map(int, args.workers.split(","))
               for version, jar in versions]
    random.Random(147).shuffle(configs)
    label = args.label
    for workers, repeat, version, jar in configs:
        args.jar = jar
        args.jar_hash = hashes[str(jar)]
        args.label = label + ("-" + version if version else "")
        run(args, workers, repeat, tile_list, tiles)


if __name__ == "__main__":
    main()
