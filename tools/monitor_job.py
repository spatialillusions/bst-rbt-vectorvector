"""Run a measured Windows job with process/physical-I/O/memory samples.

The argument after -- is an argv vector, not a shell command. Captures stdout/stderr
without modification; no unrelated process or system setting is changed.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from benchmark_contours import kernel, snapshot, DiskCounters


class SystemMemory(C.Structure):
    _fields_ = [("length", W.DWORD), ("load", W.DWORD)] + [(n, C.c_ulonglong) for n in
        ("total", "available", "total_page", "available_page", "total_virtual", "available_virtual", "extended")]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--cwd", type=Path)
    p.add_argument("--abort-after-done", type=int)
    p.add_argument("--shard-dir", type=Path)
    p.add_argument("--min-available-mib", type=int, default=0)
    p.add_argument("--min-disk-free-gib", type=float, default=0, help="Stop this child after five samples below this free-space floor on any watched volume")
    p.add_argument("--disk-path", type=Path, action="append", default=[], help="Also sample free bytes on these volumes")
    p.add_argument("--low-memory-priority", action="store_true", help="Use very low memory priority for this child only, to limit scan-cache crowding")
    p.add_argument("--low-memory-priority-after", help="Lower this child's memory priority only after this text appears in its log")
    p.add_argument("--low-memory-priority-on-pressure-mib", type=int, default=0, help="After five samples below this available-RAM level, lower only this child to memory priority 2; keep the stop floor enabled")
    p.add_argument("command", nargs=argparse.REMAINDER)
    a = p.parse_args()
    if a.low_memory_priority_on_pressure_mib and a.low_memory_priority_on_pressure_mib <= a.min_available_mib:
        p.error("Memory-priority pressure threshold must exceed the stop floor")
    cmd = a.command[1:] if a.command[:1] == ["--"] else a.command
    a.out.mkdir(parents=True, exist_ok=False)
    (a.out / "command.json").write_text(json.dumps(cmd, indent=2))
    samples = []
    disk = DiskCounters()
    start = time.perf_counter()
    aborted = False
    low_memory = 0
    memory_stopped = False
    low_disk = 0
    disk_stopped = False
    priority_lowered = False
    low_pressure = 0
    pressure_priority_lowered = False
    watched = {str(path.resolve().anchor) for path in [Path.cwd(), Path(tempfile.gettempdir()), *a.disk_path]}
    with (a.out / "run.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=a.cwd)
        handle = kernel.OpenProcess(0x1000 | 0x10 | (0x200 if a.low_memory_priority or a.low_memory_priority_after or a.low_memory_priority_on_pressure_mib else 0), False, process.pid)
        if not handle:
            process.terminate()
            raise C.WinError(C.get_last_error())
        def lower_priority(level=1):
            nonlocal priority_lowered
            kernel.SetProcessInformation.argtypes = [W.HANDLE, C.c_int, C.c_void_p, W.DWORD]
            kernel.SetProcessInformation.restype = W.BOOL
            priority = W.DWORD(level)
            if not kernel.SetProcessInformation(handle, 0, C.byref(priority), C.sizeof(priority)):
                raise C.WinError(C.get_last_error())
            priority_lowered = True
            log.write(f"MONITOR: memory priority {level} for this child only\n")
            log.flush()
        try:
            if a.low_memory_priority:
                lower_priority()
            while True:
                s = snapshot(handle)
                s.update(disk.sample())
                memory = SystemMemory()
                memory.length = C.sizeof(memory)
                kernel.GlobalMemoryStatusEx(C.byref(memory))
                s["available_physical"] = memory.available
                s["disk_free_bytes"] = {path: shutil.disk_usage(path).free for path in watched}
                s["elapsed"] = time.perf_counter() - start
                samples.append(s)
                latest = a.out / "latest.json.tmp"
                latest.write_text(json.dumps(s, indent=2))
                try:
                    latest.replace(a.out / "latest.json")
                except PermissionError:
                    # Windows readers may temporarily deny replacement. Keep the
                    # previous complete snapshot; raw samples are still retained.
                    pass
                if process.poll() is not None:
                    break
                if a.low_memory_priority_after and not priority_lowered:
                    try:
                        if a.low_memory_priority_after.encode() in (a.out / "run.log").read_bytes():
                            lower_priority()
                    except PermissionError:
                        pass
                low_pressure = low_pressure + 1 if memory.available < a.low_memory_priority_on_pressure_mib * 2**20 else 0
                if a.low_memory_priority_on_pressure_mib and low_pressure >= 5 and not priority_lowered:
                    lower_priority(2)
                    pressure_priority_lowered = True
                    log.write("MONITOR: early memory-pressure mitigation; stop floor remains enabled\n")
                    log.flush()
                low_memory = low_memory + 1 if memory.available < a.min_available_mib * 2**20 else 0
                if a.min_available_mib and low_memory >= 5 and not memory_stopped:
                    memory_stopped = True
                    log.write("MONITOR: stopping this job after five low-physical-memory samples\n")
                    log.flush()
                    process.terminate()
                low_disk = low_disk + 1 if any(free < a.min_disk_free_gib * 2**30 for free in s["disk_free_bytes"].values()) else 0
                if a.min_disk_free_gib and low_disk >= 5 and not disk_stopped:
                    disk_stopped = True
                    log.write("MONITOR: stopping this job after five low-disk-space samples\n")
                    log.flush()
                    process.terminate()
                if a.abort_after_done and a.shard_dir and not aborted:
                    if len(list(a.shard_dir.glob("*.done"))) >= a.abort_after_done:
                        aborted = True
                        process.terminate()
                time.sleep(1)
            code = process.wait()
        except BaseException:
            if process.poll() is None:
                process.terminate()
                process.wait()
            raise
        finally:
            kernel.CloseHandle(handle)
            disk.close()
    result = {"exit_code": code, "deliberate_interruption": aborted, "memory_floor_stop": memory_stopped,
              "seconds": time.perf_counter() - start,
              "peak_rss_mib": max(s["peak_rss"] for s in samples) / 2**20,
              "peak_private_mib": max(s["private"] for s in samples) / 2**20,
              "minimum_available_mib": min(s["available_physical"] for s in samples) / 2**20,
              "cpu_seconds": samples[-1]["cpu_s"], "logical_read_gib": samples[-1]["read_bytes"] / 2**30,
              "logical_write_gib": samples[-1]["write_bytes"] / 2**30}
    result["minimum_disk_free_gib"] = {path: min(s["disk_free_bytes"][path] for s in samples) / 2**30 for path in watched}
    result["disk_floor_stop"] = disk_stopped
    result["low_memory_priority"] = priority_lowered
    result["memory_pressure_priority_lowered"] = pressure_priority_lowered
    result["memory_pressure_threshold_mib"] = a.low_memory_priority_on_pressure_mib
    result["memory_priority_after_marker"] = a.low_memory_priority_after
    result["peak_system_cache_resident_mib"] = max(s.get("system_cache_resident_bytes", 0) for s in samples) / 2**20
    (a.out / "samples.json").write_text(json.dumps(samples))
    (a.out / "result.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)
    if code and not aborted:
        raise SystemExit(code)


if __name__ == "__main__":
    main()
