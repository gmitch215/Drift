"""Scores the NOT_ENVIRONMENT reading on the dev half: a 3x3 table and the precision and recall.

Reads labels/dev-*.yml and one `drift triage --json` output per dev item (slug__number.json).
Dev half only; the held-out half is never read by this script.
"""
import argparse
import glob
import json
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import corpus_id as c  # noqa: E402

ROWS = ["env-cause", "code-defect or user-error", "unclear"]
COLS = ["names a cause", "not environment", "cannot tell"]


def truth(verdict):
    if verdict == "env-cause":
        return ROWS[0]
    if verdict in ("code-defect", "user-error"):
        return ROWS[1]
    return ROWS[2]


def drift_answer(out):
    if "notEnvironment" in out:
        return COLS[1]
    if out.get("rules") or out.get("draft") is not None:
        return COLS[0]
    return COLS[2]


def load_labels():
    labels = {}
    for path in sorted(glob.glob(os.path.join(HERE, "labels", "dev-*.yml"))):
        for row in c.parse_labels(open(path, encoding="utf-8").read()):
            labels[row["id"]] = row
    return labels


def fmt(k, n):
    if n == 0:
        return "%d/%d" % (k, n)
    lo, hi = c.wilson(k, n)
    return "%d/%d (%.3f; %.3f-%.3f)" % (k, n, k / n, max(lo, 0.0), min(hi, 1.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outputs", help="directory of drift triage --json outputs for the dev items")
    args = ap.parse_args()
    labels = load_labels()
    table = {r: Counter() for r in ROWS}
    rows = []
    missing = []
    for lid, row in sorted(labels.items()):
        repo, num = lid.rsplit("#", 1)
        path = os.path.join(args.outputs, "%s__%s.json" % (repo.replace("/", "__"), num))
        if not os.path.exists(path):
            missing.append(lid)
            continue
        out = json.load(open(path, encoding="utf-8"))
        t = truth(row["verdict"])
        a = drift_answer(out)
        table[t][a] += 1
        rows.append((lid, row["verdict"], a, out.get("notEnvironment")))
    if missing:
        raise SystemExit("missing outputs for %d dev items, first %s" % (len(missing), missing[:3]))

    print("| truth | n | %s |" % " | ".join(COLS))
    print("|---|---|---|---|---|")
    for r in ROWS:
        n = sum(table[r].values())
        print("| %s | %d | %s |" % (r, n, " | ".join(str(table[r][k]) for k in COLS)))

    ne = [x for x in rows if x[2] == COLS[1]]
    nonenv = [x for x in rows if x[1] != "env-cause"]
    right = [x for x in ne if x[1] in ("code-defect", "user-error")]
    env_claims = [x for x in ne if x[1] == "env-cause"]
    print()
    envs = sum(1 for x in rows if x[1] == "env-cause")
    print("NOT_ENVIRONMENT fires on %s non-environment items." % fmt(
        sum(1 for x in nonenv if x[2] == COLS[1]), len(nonenv)))
    print("Precision against code-defect and user-error: %s." % fmt(len(right), len(ne)))
    print("Recall of code-defect and user-error: %s." % fmt(
        len(right), sum(1 for x in rows if x[1] in ("code-defect", "user-error"))))
    print("Fires on an env-cause item: %s." % fmt(len(env_claims), envs))
    for lid, verdict, _, reading in env_claims:
        print("  env-cause claim %s: %s" % (lid, json.dumps(reading)))
    for lid, verdict, _, reading in ne:
        print("  %-9s %s: %s" % (verdict, lid, json.dumps(reading)))


if __name__ == "__main__":
    main()
