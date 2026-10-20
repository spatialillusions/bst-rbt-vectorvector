"""Read-only Windows memory attribution alongside an existing job; never changes priorities.

Appends one JSON line every --interval seconds until the target process exits.
Working sets include shared pages: do not sum them as unique physical usage.
Private bytes measure committed memory, not physical residency. Uses only stdlib.
"""
from collections import defaultdict
import argparse
import ctypes as C
from ctypes import wintypes as W
from datetime import datetime, timezone
import json
from pathlib import Path
import time

from benchmark_contours import DiskCounters, Memory, kernel
from monitor_job import SystemMemory


def process_memory():
    ids = (W.DWORD * 65536)()
    used = W.DWORD()
    kernel.K32EnumProcesses.argtypes = [C.c_void_p, W.DWORD, C.POINTER(W.DWORD)]
    if not kernel.K32EnumProcesses(ids, C.sizeof(ids), C.byref(used)):
        raise C.WinError(C.get_last_error())
    kernel.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, C.POINTER(W.DWORD)]
    result = []
    inaccessible = 0
    for pid in ids[:used.value // C.sizeof(W.DWORD)]:
        handle = kernel.OpenProcess(0x1000 | 0x10, False, pid)
        if not handle:
            inaccessible += 1
            continue
        try:
            memory = Memory()
            memory.cb = C.sizeof(memory)
            if not kernel.K32GetProcessMemoryInfo(handle, C.byref(memory), C.sizeof(memory)):
                inaccessible += 1
                continue
            size = W.DWORD(32768)
            name = C.create_unicode_buffer(size.value)
            ok = kernel.QueryFullProcessImageNameW(handle, 0, name, C.byref(size))
            result.append({'pid': pid, 'name': Path(name.value).name if ok else 'unknown',
                           'rss': memory.rss, 'private': memory.private})
        finally:
            kernel.CloseHandle(handle)
    return sorted(result, key=lambda x: x['rss'], reverse=True), inaccessible


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--interval', type=float, default=15)
    parser.add_argument('--seconds', type=float, help='Optional bounded observation')
    a = parser.parse_args()
    if a.interval <= 0 or (a.seconds is not None and a.seconds <= 0):
        parser.error('Intervals/duration must be positive')
    # Keep this handle open so PID reuse cannot silently change the watched process.
    target = kernel.OpenProcess(0x1000 | 0x100000, False, a.pid)
    if not target:
        raise C.WinError(C.get_last_error())
    kernel.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
    kernel.WaitForSingleObject.restype = W.DWORD
    disk = DiskCounters()
    start = time.monotonic()
    try:
        with a.out.open('x', encoding='utf-8', buffering=1) as output:
            while kernel.WaitForSingleObject(target, 0) == 258:
                memory = SystemMemory()
                memory.length = C.sizeof(memory)
                if not kernel.GlobalMemoryStatusEx(C.byref(memory)):
                    raise C.WinError(C.get_last_error())
                processes, inaccessible = process_memory()
                groups = defaultdict(lambda: {'count': 0, 'rss': 0, 'private': 0})
                for process in processes:
                    group = groups[process['name']]
                    group['count'] += 1
                    group['rss'] += process['rss']
                    group['private'] += process['private']
                grouped = [{'name': name, **values} for name, values in groups.items()]
                grouped.sort(key=lambda p: p['rss'], reverse=True)
                row = {'utc': datetime.now(timezone.utc).isoformat(), 'elapsed': time.monotonic()-start,
                       'target_pid': a.pid, 'available_physical': memory.available,
                       'total_physical': memory.total, 'system': disk.sample(),
                       'target': next((p for p in processes if p['pid'] == a.pid), None),
                       'process_groups': grouped[:15], 'top_processes': processes[:20], 'inaccessible_processes': inaccessible}
                output.write(json.dumps(row) + '\n')
                if a.seconds is not None and time.monotonic()-start >= a.seconds:
                    break
                time.sleep(a.interval)
    finally:
        disk.close()
        kernel.CloseHandle(target)

if __name__ == '__main__':
    main()
