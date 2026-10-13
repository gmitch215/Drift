import glob
import json
import os
import statistics
import sys


def fmt(xs, unit):
    return "%.1f %s (%.1f-%.1f)" % (statistics.median(xs), unit, min(xs), max(xs))


def main(argv):
    folder = argv[1] if len(argv) > 1 else os.path.join(os.path.dirname(__file__), "results")
    rows = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(folder, "*.json")))]
    out = []
    out.append("| host | build | version ms | capture ms | diagnose ms | peak RSS MB (diagnose) | size MB | load1 |")
    out.append("|---|---|---|---|---|---|---|---|")
    for r in rows:
        w = r["workloads"]
        size = r["size"]
        mb = size.get("bytes", size.get("wasmBytes", 0)) / 1e6
        cells = [r["host"], r["label"]]
        for name in ("version", "capture", "diagnose"):
            cells.append(fmt([s["ms"] for s in w[name]["runs"]], "ms").replace(" ms", "") if name in w else "n/a")
        if "diagnose" in w:
            rss = statistics.median(s["rssKb"] for s in w["diagnose"]["runs"]) / 1024
            cells.append("%.1f" % rss)
        else:
            cells.append("n/a")
        cells.append("%.2f" % mb if "bytes" in size else "%.2f wasm, %.2f gz" % (mb, size["wasmGzipBytes"] / 1e6))
        cells.append("%.1f" % r["facts"]["load1Start"])
        out.append("| " + " | ".join(cells) + " |")
    out.append("")
    out.append("Cells show median (min-max) over %s runs after %s warm-ups." % (
        ", ".join(sorted({str(r["runs"]) for r in rows})), ", ".join(sorted({str(r["warmup"]) for r in rows}))))
    print("\n".join(out))


main(sys.argv)
