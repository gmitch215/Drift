import argparse
import glob
import gzip
import json
import os
import platform
import subprocess
import sys
import time

TIMEOUT = 120


def run_once(argv, check_output):
    start = time.perf_counter()
    out = subprocess.PIPE if check_output else subprocess.DEVNULL
    p = subprocess.Popen(argv, stdout=out, stderr=subprocess.DEVNULL)
    data = p.stdout.read() if check_output else b""
    _, status, usage = os.wait4(p.pid, 0)
    ms = (time.perf_counter() - start) * 1000.0
    p.returncode = os.waitstatus_to_exitcode(status)
    rss = usage.ru_maxrss if sys.platform.startswith("linux") else usage.ru_maxrss // 1024
    return ms, rss, p.returncode, data


def workload(argv, runs, warmup):
    for i in range(warmup):
        ms, rss, code, data = run_once(argv, i == 0)
        if code != 0 or (i == 0 and not data):
            sys.exit("warm-up failed (exit %d): %s" % (code, " ".join(argv)))
    before = os.getloadavg()[0]
    samples = []
    for _ in range(runs):
        ms, rss, code, _ = run_once(argv, False)
        if code != 0:
            sys.exit("run failed (exit %d): %s" % (code, " ".join(argv)))
        samples.append({"ms": round(ms, 3), "rssKb": rss})
    return {"argv": argv, "load1Before": before, "load1After": os.getloadavg()[0], "runs": samples}


def sizes(paths, kind):
    if kind == "wasm":
        wasm = [f for p in paths for f in glob.glob(os.path.join(p, "**", "*.wasm"), recursive=True)]
        raw = sum(os.path.getsize(f) for f in wasm)
        packed = sum(len(gzip.compress(open(f, "rb").read(), 9)) for f in wasm)
        return {"wasmBytes": raw, "wasmGzipBytes": packed, "wasmFiles": len(wasm)}
    return {"bytes": sum(os.path.getsize(p) for p in paths)}


def host_facts():
    facts = {
        "uname": " ".join(platform.uname()),
        "cpus": os.cpu_count(),
        "python": platform.python_version(),
        "load1Start": os.getloadavg()[0],
    }
    try:
        quota, period = open("/sys/fs/cgroup/cpu.max").read().split()
        if quota != "max":
            facts["cgroupCpus"] = int(quota) / int(period)
    except (OSError, ValueError):
        pass
    return facts


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--kind", required=True, choices=["native", "jvm", "wasm"])
    a.add_argument("--binary", required=True)
    a.add_argument("--host", required=True)
    a.add_argument("--label", required=True)
    a.add_argument("--out", required=True)
    a.add_argument("--runs", type=int, default=15)
    a.add_argument("--warmup", type=int, default=3)
    a.add_argument("--green", required=True)
    a.add_argument("--red", required=True)
    a.add_argument("--size-of", action="append")
    a.add_argument("--stamp", default="")
    o = a.parse_args()
    result = {
        "host": o.host,
        "kind": o.kind,
        "label": o.label,
        "stamp": o.stamp,
        "runs": o.runs,
        "warmup": o.warmup,
        "timer": "python perf_counter around Popen + os.wait4 (ru_maxrss per child)",
        "facts": host_facts(),
        "size": sizes(o.size_of or [o.binary], o.kind),
        "workloads": {},
    }
    if o.kind == "jvm":
        java = os.environ.get("DRIFT_JAVA", "java")
        v = subprocess.run([java, "-version"], capture_output=True, text=True)
        result["facts"]["java"] = v.stderr.splitlines()[0] if v.stderr else "unknown"
    if o.kind == "wasm":
        result["note"] = "size only: the Wasm build has no CLI executable, files or process access"
    else:
        b = o.binary
        for name, argv in (
            ("version", [b, "--version"]),
            ("capture", [b, "capture", "--label", "perf"]),
            ("diagnose", [b, "diagnose", o.green, o.red]),
        ):
            result["workloads"][name] = workload(argv, o.runs, o.warmup)
    os.makedirs(os.path.dirname(os.path.abspath(o.out)), exist_ok=True)
    with open(o.out, "w") as f:
        json.dump(result, f, indent=2, sort_keys=True)
        f.write("\n")


main()
