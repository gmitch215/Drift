#!/usr/bin/env python3
"""Reports, per target, where each probe's transcript changes along the version axis.

usage: axis.py DIR   (DIR holds <version>-<target>.txt transcripts)
"""
import re
import sys
from pathlib import Path


def key(version):
    nums = [int(p) for p in re.findall(r"\d+", version)]
    return nums


def read(path):
    probes, status, body, current = {}, {}, [], None
    for line in path.read_text(encoding="utf-8").split("\n")[:-1]:
        if line.startswith("@@ "):
            if current:
                probes[current] = "\n".join(body)
            rest = line[3:]
            tagged = rest.endswith("]") and " [" in rest
            current = rest.rsplit(" [", 1)[0] if tagged else rest
            status[current] = rest.rsplit(" [", 1)[1][:-1] if tagged else "OK"
            body = []
        elif current:
            body.append(line)
    if current:
        probes[current] = "\n".join(body)
    return probes, status


def main(root):
    cols = {}
    for p in sorted(Path(root).glob("*.txt")):
        version, target = p.stem.split("-", 1)
        cols.setdefault(target, {})[version] = read(p)
    for target in sorted(cols):
        versions = sorted(cols[target], key=key)
        print(f"== {target}: {' '.join(versions)}")
        for v in versions:
            probes, status = cols[target][v]
            ok = sum(1 for s in status.values() if s == "OK")
            print(f"   {v}: {ok} ok, {len(status) - ok} unavailable of {len(status)}")
        ids = sorted(cols[target][versions[0]][0])
        for pid in ids:
            prev = None
            for v in versions:
                probes, status = cols[target][v]
                now = (status[pid], probes[pid] if status[pid] == "OK" else None)
                if prev is not None and now != prev[1]:
                    kind = (
                        "value change"
                        if now[0] == "OK" and prev[1][0] == "OK"
                        else f"{prev[1][0]} -> {now[0]}"
                    )
                    detail = ""
                    if kind == "value change":
                        a, b = prev[1][1].split("\n"), now[1].split("\n")
                        n = sum(1 for x, y in zip(a, b) if x != y) + abs(len(a) - len(b))
                        first = next(((x, y) for x, y in zip(a, b) if x != y), ("", ""))
                        detail = f" ({n} lines; {first[0]!r} -> {first[1]!r})"
                    print(f"  {pid}: {prev[0]} -> {v}: {kind}{detail}")
                prev = (v, now)


main(sys.argv[1])
