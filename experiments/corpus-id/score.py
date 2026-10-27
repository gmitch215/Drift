import argparse
import glob
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_id as c  # noqa: E402
import replay  # noqa: E402

ROOT = c.ROOT
TABLE_TOOLS = set("java kotlin gradle node npm python php go rust git dotnet workerd pip cc libc".split())
TABLE_KEYS = set(
    "os.name os.version os.arch os.release.ID os.release.VERSION_ID project.gradle.kotlin.version "
    "project.gradle.agp.version browser.name browser.version ci.provider ci.runner.label "
    "runtime.platform runtime.container.image runtime.jvm.options env.LANG env.TZ".split()
)
DETECT_RE = re.compile(r"^detect:\n(.*?)(?=^\S|\Z)", re.S | re.M)
KEY_RE = re.compile(r"(?:^|[\s-])(?:a|b):\s*['\"]?([^\s'\"#]+)", re.M)


def family(key):
    p = key.split(".")
    if p[0] in ("tool", "project", "env") and len(p) > 1:
        return ".".join(p[:2])
    return p[0]


def in_table(key):
    p = key.split(".")
    if key in TABLE_KEYS:
        return True
    if p[0] == "tool" and len(p) > 1 and p[1] in TABLE_TOOLS:
        return True
    return p[0] == "project" and len(p) > 2


def detect_keys(yaml_text):
    m = DETECT_RE.search(yaml_text)
    return set(KEY_RE.findall(m.group(1))) if m else set()


def agrees(a, b):
    x = a.strip().lower().lstrip("v").split(".")
    y = b.strip().lower().lstrip("v").split(".")
    n = min(len(x), len(y))
    return n > 0 and x[:n] == y[:n]


def load_rules():
    rules, text = {}, []
    for path in sorted(glob.glob(os.path.join(ROOT, "rules", "**", "*.yml"), recursive=True)):
        t = open(path, encoding="utf-8").read()
        rules[os.path.basename(path)[:-4]] = detect_keys(t)
        text.append(t)
    return rules, "\n".join(text)


def contaminated(repo, number, pr, text):
    pats = []
    for n in [number] + ([pr] if pr else []):
        pats += [r"%s/issues/%d\b" % (re.escape(repo), n), r"%s/pull/%d\b" % (re.escape(repo), n),
                 r"%s#%d\b" % (re.escape(repo), n)]
        if repo == "python/cpython":
            pats.append(r"gh-%d\b" % n)
    return any(re.search(p, text) for p in pats)


KNOWN_FIELDS = {"ask", "author", "body", "claims", "draft", "facts", "notEnvironment", "rules", "schema", "truncated"}


def load_out(path, rules):
    return parse_out(json.load(open(path)), os.path.basename(path), rules)


def parse_out(d, name, rules):
    extra = set(d) - KNOWN_FIELDS
    if extra:
        raise SystemExit("output %s has fields %s: define how they map to the answers before scoring" % (name, sorted(extra)))
    facts = [(f["key"], f["value"], f["side"]) for f in d["facts"]]
    rule_ids = [r["rule"] for r in d["rules"]]
    rule_keys = set()
    for r in rule_ids:
        rule_keys |= rules.get(r, set())
    draft = d.get("draft")
    draft_keys = detect_keys(draft["yaml"]) if draft else set()
    ask = [{"family": a["family"], "command": a["command"]} for a in d.get("ask", [])]
    return {"facts": facts, "rules": rule_ids, "rule_keys": rule_keys, "draft": bool(draft),
            "draft_keys": draft_keys, "claims": len(d["claims"]), "ask": ask,
            "not_env": "notEnvironment" in d, "ne_signal": (d.get("notEnvironment") or {}).get("signal")}


def cause_set(o):
    return {k for k in (o["rule_keys"] | o["draft_keys"]) if k != "run:signature"}


def hits(o, a, before, after):
    fam = family(a)
    fkeys = {f[0] for f in o["facts"]}
    ck = cause_set(o)
    values = [v for v in (before, after) if v]
    return {
        "fact": any(family(k) == fam for k in fkeys),
        "fact-exact": a in fkeys,
        "rule": any(family(k) == fam for k in o["rule_keys"]),
        "rule-exact": a in o["rule_keys"],
        "named": a in fkeys or a in ck,
        "cause": a in ck,
        "value": any(f[0] == a and any(agrees(f[1], v) for v in values) for f in o["facts"]),
    }


def fmt(k, n):
    if n == 0:
        return "0/0"
    lo, hi = c.wilson(k, n)
    lo, hi = max(lo, 0.0), min(hi, 1.0)
    return "%d/%d (%.3f; %.3f-%.3f)" % (k, n, k / n, lo, hi)


def anything(o):
    return {
        "fact": bool(o["facts"]),
        "fact-nonos": any(not f[0].startswith("os.") and f[0] != "run:signature" for f in o["facts"]),
        "rule": bool(o["rules"]),
        "draft": o["draft"],
        "rule-or-draft": bool(o["rules"]) or o["draft"],
        "any": bool(o["facts"]) or bool(o["rules"]) or o["draft"],
    }


DETAIL = {
    "TasmoAdmin/TasmoAdmin#824": "hand-read: the version is in a Composer error, `You are running 7.4.33`",
    "actions/runner#334": "hand-read: two image names in prose and a code span, no `image:` or `FROM` line",
    "jpenilla/MiniMOTD#112": "hand-read: platform appears only in jar names inside a stack trace",
    "jpenilla/MiniMOTD#53": "hand-read: platform appears in a log line, `This server is running Travertine version`",
    "mottosso/Qt.py#465": "hand-read: PyQt5 and PyQt6 named in prose, no install command or build file",
    "gmitch215/MobChip#78": "hand-read: `Server Version: Paper 1.20.1` is a form field, no key reads it",
    "rust-lang/rust#105167": "hand-read: the runner label change is stated in a later comment, not the issue",
    "php/php-src#8150": "hand-read: PHP versions appear in a form field; no rule on the 8.0 bcmath locale change",
}


def ask_hits(o, a):
    """An ask reads the family of attribute a: its family is a or a key prefix of a at a dot."""
    return any(replay.hit(x["family"], a) for x in o["ask"])


def answer(o):
    """The one answer of a Drift output: cause, not environment, ask or cannot tell."""
    if cause_set(o):
        return "cause"
    if o["not_env"]:
        return "not environment"
    if o["ask"]:
        return "ask"
    return "cannot tell"


def correct(i, strict=False):
    """The answer table of the 2026-10-27 amendment. strict: cannot tell on unclear is not correct."""
    v, o, a = i["verdict"], i["o"], answer(i["o"])
    if v == "env-cause":
        if i["attribute"] == "unnamed":
            return a == "ask"
        return (a == "cause" and i["attribute"] in cause_set(o)) or (a == "ask" and ask_hits(o, i["attribute"]))
    if v in ("code-defect", "user-error"):
        return a == "not environment"
    return a == "ask" or (a == "cannot tell" and not strict)


def always_ask_ceiling(items):
    return sum(1 for i in items if i["verdict"] in ("env-cause", "unclear"))


def three_way(items, w):
    w("## Four-Class Answers\n")
    w("Truth: environment (env-cause), not environment (code-defect, user-error) or unclear. Drift's answer is one of: names a cause (a matched rule or draft rule), says not environment, asks (a non-empty `ask` list) or cannot tell. "
      "Correct answers (amendment 2026-10-27): env-cause with a named attribute, a cause that holds the exact key, or an ask whose family is the attribute or a key prefix of it; env-cause, unnamed: an ask; code-defect or user-error: not environment; unclear: an ask, or cannot tell (reading: the answer that was correct before the ask existed stays correct; the strict row below drops it).\n")
    cols = ["names the labelled cause", "names another cause", "says not environment", "asks for the family", "asks for something else", "cannot tell"]
    rows = []
    groups = [("environment, named attribute", [i for i in items if i["verdict"] == "env-cause" and i["attribute"] != "unnamed"]),
              ("environment, unnamed attribute", [i for i in items if i["verdict"] == "env-cause" and i["attribute"] == "unnamed"]),
              ("not environment: code-defect", [i for i in items if i["verdict"] == "code-defect"]),
              ("not environment: user-error", [i for i in items if i["verdict"] == "user-error"]),
              ("unclear", [i for i in items if i["verdict"] == "unclear"])]
    for name, g in groups:
        cnt = Counter()
        for i in g:
            a = answer(i["o"])
            if a == "cannot tell":
                cnt["cannot tell"] += 1
            elif a == "not environment":
                cnt["says not environment"] += 1
            elif a == "ask":
                named = i["verdict"] == "env-cause" and i["attribute"] != "unnamed"
                cnt["asks for the family" if named and ask_hits(i["o"], i["attribute"]) else "asks for something else"] += 1
            elif i["verdict"] == "env-cause" and i["attribute"] != "unnamed" and i["attribute"] in cause_set(i["o"]):
                cnt["names the labelled cause"] += 1
            else:
                cnt["names another cause"] += 1
        rows.append([name, len(g)] + [cnt[k] for k in cols])
    rows.append(["all", len(items)] + [sum(r[2 + k] for r in rows) for k in range(len(cols))])
    w(table(["truth", "n"] + cols, rows))
    n = len(items)
    ok = sum(1 for i in items if correct(i))
    strict = sum(1 for i in items if correct(i, True))
    byv = lambda vs, st=False: (sum(1 for i in items if i["verdict"] in vs and correct(i, st)), sum(1 for i in items if i["verdict"] in vs))
    w("Four-class accuracy: %s. Per truth class, correct: environment %s, not environment %s, unclear %s." % (
        fmt(ok, n), fmt(*byv(("env-cause",))), fmt(*byv(("code-defect", "user-error"))), fmt(*byv(("unclear",)))))
    w("Strict reading (cannot tell on unclear is not correct): %s; unclear %s.\n" % (fmt(strict, n), fmt(*byv(("unclear",), True))))
    ceiling = always_ask_ceiling(items)
    w("Sanity row, a tool that always asks: correct on at most the env-cause and unclear items, %s, which must stay under the accuracy bar of 0.80.\n" % fmt(ceiling, n))
    asks = Counter()
    for i in items:
        if i["o"]["ask"]:
            asks[i["verdict"]] += 1
    tot = Counter(i["verdict"] for i in items)
    w("Share of items with an ask, by truth: " + ", ".join("%s %s" % (v, fmt(asks[v], tot[v])) for v in ("env-cause", "code-defect", "user-error", "unclear") if tot[v]) + ".\n")


def without_plain(d):
    return {k: v for k, v in d.items() if k != "plain"}


def loop(half, items, rules, w):
    """The ask and reply loop on the named env-cause items (replay.py). Not a ratified bar."""
    data = replay.load(half)
    w("## Ask and Reply Loop\n")
    w("Not a ratified bar. Stage 1: Drift's first answer holds a text fact for the labelled family, or an ask whose family is that family. "
      "Stage 2: the ask is answered through `drift why --reply` with a synthetic reply (a hand-written template per family filled with the label's failing-side value, or a placeholder when the label has none) "
      "and the table asks whether the session then states a fact for the family and whether a matched rule reads the labelled key. "
      "Stage 2 measures ask coverage and the parse round trip. It does not measure diagnosis: the reply is made up from the label, not read from a machine. "
      "Stage 3 answers the next step, the works-side check, through `drift why --reply --works` with the same template filled with the label's `before` value (or a placeholder) and counts the items whose answer names the labelled family as the difference.\n")
    if data is None:
        w("Not run for this half: no replay outputs (`replay.py prepare`, `replay-lane.sh a`, `replay.py reply`, `replay-lane.sh b`).\n")
        return
    tune_path = os.path.join(c.hdir(), "tune-ids.txt")
    tune = set(open(tune_path).read().split()) if os.path.exists(tune_path) else None
    named = [i for i in items if i["verdict"] == "env-cause" and i["attribute"] != "unnamed" and i["id"] in data]
    groups = [("all", named)]
    if tune is not None:
        groups = [("tune", [i for i in named if i["id"] in tune]), ("validate", [i for i in named if i["id"] not in tune])] + groups
    rows = []
    for name, g in groups:
        k = Counter()
        for i in g:
            row = data[i["id"]]
            a = parse_out(without_plain(row["a"]), i["id"], rules)
            attr = replay.norm(i["attribute"])
            fam = family(attr)
            fact = any(family(f[0]) == fam and f[2] == "failing" for f in a["facts"])
            asked = ask_hits(a, i["attribute"])
            k["fact"] += fact
            k["ask"] += asked and not fact
            k["stage1"] += fact or asked
            replayed = row["b"] is not None
            k["replayed"] += replayed
            final = parse_out(without_plain(row["b"]), i["id"], rules) if replayed else a
            stated = any(family(f[0]) == fam and f[2] == "failing" for f in final["facts"])
            k["stated"] += (fact or asked) and (stated or not replayed and fact)
            k["no template"] += asked and not replayed
            real = replayed and not row["m"].get("placeholder", True)
            k["real value"] += real
            k["cause"] += real and (attr in cause_set(final))
            works = row.get("c")
            if works is not None:
                k["works replayed"] += 1
                found = (works.get("plain") or {}).get("findings", [])
                k["works names"] += any(family(f["path"]) == fam for f in found)
        n = len(g)
        rows.append([name, n, fmt(k["fact"], n), fmt(k["ask"], n), fmt(k["stage1"], n), fmt(k["no template"], max(k["ask"], 0)),
                     fmt(k["stated"], n), fmt(k["cause"], k["real value"]),
                     fmt(k["works names"], k["works replayed"])])
    w(table(["set", "named items", "text fact", "ask only", "stage 1", "ask without a template", "stage 2: session states the family", "stage 2: rule names the key (items with a label value)",
                  "stage 3: works-side reply names the fact as the difference (replayed)"], rows))


def clean_ask(out):
    return any(a["family"] == "clean-run" for a in out.get("ask", []))


def ends_not_environment(out):
    return out is not None and (out.get("notEnvironment") or {}).get("signal") == "clean-run"


def ends_in_capture(out):
    ask = ((out or {}).get("plain") or {}).get("ask") or []
    return bool(ask) and ask[0]["family"] == "capture"


def clean_loop(half, w):
    """The clean-run ask and its round trip on every dev item (replay.py). Not a ratified bar."""
    data = replay.load_clean(half)
    w("## Clean Run Loop\n")
    w("Not a ratified bar. Reach: the first `drift why --json` answer holds a `clean-run` ask (the report has a runnable command, a failure signature and a toolchain with an official image). "
      "Round trip: the ask is answered through the real `drift why --reply`. The same-failure reply is a hand-written template per signature kind filled with the failure signature Drift extracted from the item's own body (the `run:signature` fact), so it exists only for items whose text carries a signature. "
      "The works reply is one line without an error. Stage 2 measures ask coverage and the parse round trip, not diagnosis: the replies are made up, not read from a clean machine. "
      "Hard condition: a works reply never ends in NOT_ENVIRONMENT. A same-failure reply on an env-cause item does end in NOT_ENVIRONMENT, which is the correct reading when a clean machine reproduces the failure, and the count is recorded here.\n")
    if data is None:
        w("Not run for this half: no clean replay outputs (`replay.py prepare-clean`, `replay-lane.sh a`, `replay.py clean-reply`, `replay-lane.sh b` with `same` and `works`).\n")
        return
    tune_path = os.path.join(c.hdir(), "tune-ids.txt")
    tune = set(open(tune_path).read().split()) if os.path.exists(tune_path) else None
    classes = [("code-defect and user-error", ("code-defect", "user-error")), ("env-cause", ("env-cause",)), ("unclear", ("unclear",))]
    sets = [("all", None)]
    if tune is not None:
        sets = [("tune", True), ("validate", False)] + sets
    rows = []
    for sname, want in sets:
        for cname, verdicts in classes:
            g = [r for i, r in data.items() if r["m"]["verdict"] in verdicts and (want is None or (i in tune) == want)]
            n = len(g)
            asked = [r for r in g if clean_ask(r["a"])]
            texted = sum(any(a["family"] == "error-text" for a in r["a"].get("ask", [])) for r in g)
            with_same = [r for r in asked if r["same"] is not None]
            with_works = [r for r in asked if r["works"] is not None]
            signed = [r for r in g if replay.signature_of(r["a"]) is not None]
            rows.append([sname, cname, n, len(signed), sum(r["prompt"] for r in g), fmt(len(asked), n), texted, len(with_same),
                         fmt(sum(ends_not_environment(r["same"]) for r in with_same), len(with_same)),
                         len(with_works), fmt(sum(ends_in_capture(r["works"]) for r in with_works), len(with_works)),
                         sum(ends_not_environment(r["works"]) for r in with_works)])
    w(table(["set", "class", "items", "with a failure signature", "with a command-looking line", "reach (clean-run ask)", "error-text ask (command, no error)", "same-failure replies", "same: ends in NOT_ENVIRONMENT",
             "works replies", "works: next ask is the capture", "works: ends in NOT_ENVIRONMENT"], rows))


def bars(items, primary, others, w):
    w("## Ratified Bars (Amendment 2026-10-27)\n")
    w("Pass or miss is against the bar as ratified. Dev numbers are development signals; the held-out half decides.\n")
    rows = []

    def row(name, k, n, bar_text, passed, extra=""):
        rows.append([name, fmt(k, n), bar_text, "pass" if passed else "miss", extra])

    def share(k, n):
        return k / n if n else 0.0

    named_k = sum(1 for i in primary if i["hits"]["named"])
    n = len(primary)
    row("exact attribute named (fact key or cause key)", named_k, n, "above 0.65", share(named_k, n) > 0.65)
    asked_k = sum(1 for i in primary if i["hits"]["named"] or (answer(i["o"]) == "ask" and ask_hits(i["o"], i["attribute"])))
    row("exact attribute named, or its family asked", asked_k, n, "above 0.65 (reading with asks)", share(asked_k, n) > 0.65)
    fact_k = sum(1 for i in primary if i["hits"]["fact"])
    row("facts for the labelled family", fact_k, n, "above 0.70", share(fact_k, n) > 0.70)
    quiet = sum(1 for i in others if not i["o"]["rules"] and not i["o"]["draft"])
    row("null baseline: no rule or draft on non-named items", quiet, len(others), "at least 0.50", share(quiet, len(others)) >= 0.50)
    calls = [i for i in items if answer(i["o"]) == "not environment"]
    right = [i for i in calls if i["verdict"] in ("code-defect", "user-error")]
    env_calls = [i for i in calls if i["verdict"] == "env-cause"]
    lo = c.wilson(len(right), len(calls))[0] if calls else 0.0
    ne_pass = bool(calls) and share(len(right), len(calls)) >= 0.95 and lo > 0.80 and not env_calls
    row("not environment, precision on its calls", len(right), len(calls), "point at least 0.95, Wilson lower above 0.80, no env-cause call", ne_pass,
        "Wilson lower %.3f; env-cause items called: %d" % (lo, len(env_calls)))
    resolved = [i for i in items if i["verdict"] != "unclear" and cause_set(i["o"])]
    good = [i for i in resolved if i["verdict"] == "env-cause" and i["attribute"] != "unnamed" and i["attribute"] in cause_set(i["o"])]
    plo = c.wilson(len(good), len(resolved))[0] if resolved else 0.0
    row("`cause` precision", len(good), len(resolved), "point at least 0.85, lower at least 0.65", bool(resolved) and share(len(good), len(resolved)) >= 0.85 and plo >= 0.65,
        "Wilson lower %.3f" % plo)
    ok = sum(1 for i in items if correct(i))
    alo = c.wilson(ok, len(items))[0] if items else 0.0
    row("four-class accuracy", ok, len(items), "point at least 0.80, lower at least 0.72", bool(items) and share(ok, len(items)) >= 0.80 and alo >= 0.72,
        "Wilson lower %.3f" % alo)
    w(table(["bar", "now", "ratified", "result", "note"], rows))
    w("`dev solves` (resolved share of the cause-bearing scenarios, at least 0.80) is measured by the bench, not by this scorer.\n")
    by = {}
    for i in calls:
        by.setdefault(i["o"]["ne_signal"] or "unknown", []).append(i)
    if by:
        w("Not environment by signal:\n")
        w(table(["signal", "fires", "correct (code-defect or user-error)", "wrong: unclear", "wrong: env-cause"],
                [[sig, len(g), sum(1 for i in g if i["verdict"] in ("code-defect", "user-error")),
                  sum(1 for i in g if i["verdict"] == "unclear"), sum(1 for i in g if i["verdict"] == "env-cause")]
                 for sig, g in sorted(by.items())]))
    if calls:
        w("Items called not environment: " + ", ".join("%s (%s)" % (i["id"], i["verdict"]) for i in calls) + ".\n")


def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(str(x) for x in r) + " |" for r in rows]
    return "\n".join(out) + "\n"


def gap(item, o, key_seen, rules):
    a = item["attribute"]
    if a == "unnamed":
        return "labeller gave no attribute"
    if item["hits"]["cause"]:
        return "hit"
    if a not in key_seen and not in_table(a):
        return "no extractor for it"
    printed = item["hits"]["fact"]
    if printed:
        n = sum(1 for keys in rules.values() if a in keys)
        return "no rule for it" if not n else "no rule for this change (%d catalog rules read the key)" % n
    text = item["text"].lower()
    tool = a.split(".")[1].lower() if a.startswith(("tool.", "project.")) and "." in a else ""
    probes = [v.lower() for v in (item["after"], item["before"]) if v]
    if (probes and any(p in text for p in probes)) or (tool and tool in text):
        return "wording unmatched"
    return "sensor not captured (not in the issue text)"


def tier_a(args, hd, rules, stratum, eco, items, w):
    sample = []
    for row in c.github_repos(c.manifest()):
        for h in c.read_json(os.path.join(hd, "tier-a", c.slug(row["repo"]) + ".sample.json")) or []:
            h["repo"] = row["repo"]
            path = os.path.join(hd, "drift-out", "a", "%s__%d.json" % (c.slug(row["repo"]), h["number"]))
            if os.path.exists(path):
                h["o"] = load_out(path, rules)
                sample.append(h)
    w("## Tier A (Weak Truth), Dev\n")
    w("Weak truth: the class comes from the files the closing pull request changed, not from what a person read. A pull request that bumps a pin can fix a code bug. "
      "%d sampled items with a Drift output; Tier A is never pooled with Tier B.\n" % len(sample))
    classes = Counter(h["class"] for h in sample)
    w("Classes: " + ", ".join("%s %d" % kv for kv in classes.most_common()) + ".\n")
    rows = []
    for name, g in (("env-or-config-change", [h for h in sample if h["class"] == "env-or-config-change"]),
                    ("code-change", [h for h in sample if h["class"] == "code-change"]),
                    ("all sampled", sample)):
        a = [anything(h["o"]) for h in g]
        rows.append([name, len(g)] + [fmt(sum(x[k] for x in a), len(g)) for k in ("fact", "fact-nonos", "rule", "draft", "rule-or-draft", "any")])
    w(table(["class", "n", "any fact", "fact (not os, signature)", "any rule", "draft rule", "rule or draft", "anything"], rows))
    one = [h for h in sample if h["class"] == "env-or-config-change" and h.get("keys") and len(h["keys"]) == 1]
    for h in one:
        h["hits"] = hits(h["o"], h["keys"][0], None, None)
    measures = ("fact", "fact-exact", "rule", "rule-exact", "named", "cause")
    rows = [["env-or-config-change, one derived attribute", len(one)] + [fmt(sum(1 for h in one if h["hits"][k]), len(one)) for k in measures]]
    for h_in, label in ((True, "in Drift's key table"), (False, "outside the key table")):
        g = [h for h in one if in_table(h["keys"][0]) == h_in]
        rows.append(["  " + label, len(g)] + [fmt(sum(1 for h in g if h["hits"][k]), len(g)) for k in measures])
    w(table(["set", "n"] + list(measures), rows))
    resolved = [h for h in sample if cause_set(h["o"])]
    good = [h for h in resolved if h in one and h["keys"][0] in cause_set(h["o"])]
    w("Items with a non-empty cause set: %d; of those, `env-or-config-change` with the derived attribute in the cause set: %s.\n" % (len(resolved), fmt(len(good), len(resolved))))
    w("Derived attributes: " + ", ".join("`%s` %d" % kv for kv in Counter(h["keys"][0] for h in one).most_common()) + ".\n")
    agreement(hd, items, w)


def agreement(hd, items, w):
    cache = os.path.join(hd, "b-closing-files.json")
    files = c.read_json(cache) or {}
    need = [i for i in items if i["closing"].get("kind") == "pr" and i["id"] not in files]
    if need:
        gql = c.Gql(c.BASE)
        by_repo = defaultdict(list)
        for i in need:
            by_repo[i["repo"]].append(i)
        for repo, group in by_repo.items():
            owner, name = repo.split("/", 1)
            body = " ".join("p%d:pullRequest(number:%d){number files(first:%d){totalCount nodes{path}}}" % (j, i["pr"], c.MAX_PR_FILES) for j, i in enumerate(group))
            data = gql.call(c.BATCH_QUERY % body, owner=owner, name=name)["data"]["repository"]
            for j, i in enumerate(group):
                pr = data.get("p%d" % j)
                files[i["id"]] = {"files": [f["path"] for f in pr["files"]["nodes"]], "total": pr["files"]["totalCount"]} if pr else None
        c.write_json(cache, files)
    cross = defaultdict(Counter)
    for i in items:
        if i["closing"].get("kind") != "pr":
            col = "closing commit" if i["closing"].get("kind") == "commit" else "no closing reference"
        elif files.get(i["id"]) is None:
            col = "pull request not readable"
        else:
            col = c.classify(files[i["id"]]["files"], files[i["id"]]["total"])
        cross[i["verdict"]][col] += 1
    cols = ["env-or-config-change", "code-change", "pull request not readable", "closing commit", "no closing reference"]
    w("### Weak Rule Against the Labels\n")
    w("The Tier A class rule applied to the closing pull request of each labelled Tier B item.\n")
    w(table(["Tier B verdict"] + cols, [[v] + [cross[v][k] for k in cols] for v in ("env-cause", "code-defect", "user-error", "unclear")]))
    ec, cd = cross["env-cause"], cross["code-defect"]
    w("Of the env-cause items with a readable closing pull request, %s are classed `env-or-config-change`; of the code-defect items, %s are classed `code-change`.\n"
      % (fmt(ec["env-or-config-change"], ec["env-or-config-change"] + ec["code-change"]), fmt(cd["code-change"], cd["env-or-config-change"] + cd["code-change"])))


def self_check():
    assert family("tool.node.version") == "tool.node" and family("os.release.ID") == "os"
    assert family("project.python.numpy") == "project.python" and family("env.TZ") == "env.TZ"
    assert in_table("tool.php.version") and not in_table("tool.docker.version") and in_table("project.npm.react")
    assert agrees("8.1", "8.1.0") and agrees("v20", "20.1.2") and not agrees("18", "20")
    text = "id: x\ndetect:\n  all:\n    - a: tool.python.version\n      ver: '<3.12'\n    - b: run:signature\nseverity: low\n"
    assert detect_keys(text) == {"tool.python.version", "run:signature"}
    o = {"facts": [("tool.node.version", "20.1.0", "failing")], "rule_keys": {"tool.node.version"}, "draft_keys": set()}
    h = hits(o, "tool.node.version", "18", "20")
    assert h["cause"] and h["fact"] and h["value"] and not hits(o, "os.name", None, None)["named"]
    assert fmt(1, 4).startswith("1/4 (0.250;") and contaminated("a/b", 5, None, "see a/b#5 here")
    base = {"facts": [], "rules": [], "rule_keys": set(), "draft": False, "draft_keys": set(), "claims": 0, "ask": [], "not_env": False}

    def item(verdict, attribute, **kw):
        return {"verdict": verdict, "attribute": attribute, "o": dict(base, **kw)}

    asks = [{"family": "tool.node", "command": "node --version"}]
    assert ask_hits({"ask": asks}, "tool.node.version") and not ask_hits({"ask": asks}, "tool.nodejs.version")
    assert answer(dict(base, ask=asks)) == "ask" and answer(dict(base, not_env=True)) == "not environment"
    assert answer(dict(base, rule_keys={"tool.node.version"}, ask=asks)) == "cause" and answer(base) == "cannot tell"
    assert correct(item("env-cause", "tool.node.version", ask=asks)) and not correct(item("env-cause", "os.name", ask=asks))
    assert correct(item("env-cause", "unnamed", ask=asks)) and not correct(item("env-cause", "unnamed", not_env=True))
    assert correct(item("code-defect", None, not_env=True)) and not correct(item("user-error", None, ask=asks))
    assert not correct(item("code-defect", None)) and correct(item("unclear", None, ask=asks))
    assert correct(item("unclear", None)) and not correct(item("unclear", None), strict=True) and not correct(item("unclear", None, not_env=True))
    assert not correct(item("env-cause", "tool.node.version", not_env=True))
    assert clean_ask({"ask": [{"family": "clean-run"}]}) and not clean_ask({"ask": [{"family": "tool.node"}]})
    assert ends_not_environment({"notEnvironment": {"signal": "clean-run"}}) and not ends_not_environment({"notEnvironment": {"signal": "frame"}})
    assert ends_in_capture({"plain": {"ask": [{"family": "capture"}]}}) and not ends_in_capture(None)
    assert replay.signature_kind("npm ERR! code ELIFECYCLE") == "npm" and replay.signature_kind("TypeError: x is not a function") == "exception"
    assert replay.signature_kind("error: linker failed") == "line"
    print("ok: scorer helpers")


def main():
    if "--check" in sys.argv:
        self_check()
        return
    ap = argparse.ArgumentParser()
    ap.add_argument("--half", default="dev", choices=("dev", "heldout"))
    ap.add_argument("--version", default="")
    ap.add_argument("--binary-sha", default="")
    ap.add_argument("--only-ids", help="file with one thread id per line: score only those items (no Tier A, no hash file)")
    ap.add_argument("--out", default=os.path.join(ROOT, "experiments", "results", "corpus-id", "latest.md"))
    args = ap.parse_args()
    c.HALF = args.half
    hd = c.hdir()
    m = c.manifest()
    stratum = {r["repo"]: r["stratum"] for r in m["repos"]}
    eco = c.strata()
    rules, rule_text = load_rules()
    catalog_keys = set().union(*rules.values())

    only = set(open(args.only_ids, encoding="utf-8").read().split("\n")) - {""} if args.only_ids else None
    labels = {}
    for f in sorted(glob.glob(os.path.join(c.HERE, "labels", "%s-*.yml" % args.half))):
        for r in c.parse_labels(open(f, encoding="utf-8").read()):
            if only is None or r["id"] in only:
                labels[r["id"]] = r
    items = []
    for lid, r in sorted(labels.items()):
        repo, num = lid.rsplit("#", 1)
        num = int(num)
        t = c.read_json(os.path.join(hd, "threads", c.slug(repo), "%d.json" % num))
        problems = c.check_label(r, t, m["salt"])
        verdict = r["verdict"]
        quote_invalid = bool(problems) and verdict != "unclear"
        if quote_invalid and verdict == "env-cause":
            verdict = "unclear"
        o = load_out(os.path.join(hd, "drift-out", "b", "%s__%d.json" % (c.slug(repo), num)), rules)
        pr = (t.get("closing") or {}).get("number")
        item = {
            "id": lid, "repo": repo, "number": num, "verdict": verdict, "label_verdict": r["verdict"],
            "attribute": r.get("attribute"), "before": r.get("before"), "after": r.get("after"),
            "quote": r.get("quote"), "comment": r.get("comment"), "o": o, "pr": pr,
            "closing": t.get("closing") or {}, "text": c.issue_text(t["title"], t["body"]),
            "quote_invalid": quote_invalid, "problems": problems,
            "contaminated": contaminated(repo, num, pr, rule_text),
        }
        a = item["attribute"] if verdict == "env-cause" else None
        item["hits"] = hits(o, a, item["before"], item["after"]) if a and a != "unnamed" else None
        items.append(item)

    key_seen = set()
    for path in glob.glob(os.path.join(hd, "drift-out", "*", "*.json")):
        for f in json.load(open(path))["facts"]:
            key_seen.add(f["key"])

    named = [i for i in items if i["verdict"] == "env-cause" and i["attribute"] != "unnamed"]
    primary = [i for i in named if not i["contaminated"]]
    others = [i for i in items if i not in named]
    unnamed_env = [i for i in items if i["verdict"] == "env-cause" and i["attribute"] == "unnamed"]
    out = []
    w = out.append
    if args.half == "heldout":
        w("# Corpus Identification, Held-Out Half\n")
        w("**This is the held-out half, scored once for this Drift version. Nothing was tuned on it.** "
          "The dev half is the development signal.\n")
    else:
        w("# Corpus Identification, Dev Half\n")
        w("**This is the dev half. It is a development signal for tuning the extractor and the rules and is not the held-out result.** "
          "The held-out half is scored once per Drift version.\n")
    w("Question: given a real closed issue as filed, does `drift triage` name the cause that the maintainers later gave? "
      "Method, definitions and the bars are in `experiments/corpus-id/README.md`. Drift sees only the issue title and body as filed.\n")
    w("| | |\n|---|---|\n| Drift | %s, binary sha256 `%s` |\n| tree | HEAD `%s` plus uncommitted changes |\n| host | lane `r2` on paisley-park (container) |\n| regenerate | `experiments/corpus-id/score.sh --half %s` |\n"
      % (args.version, args.binary_sha, os.popen("git -C %s rev-parse --short HEAD" % ROOT).read().strip(), args.half))

    # coverage
    w("## What Drift Prints\n")
    w("Share of items where `drift triage` printed anything. `fact (not os, signature)` counts a fact other than `os.*` and `run:signature`.\n")
    groups = [("all %d labelled items" % len(items), items),
              ("env-cause, named attribute", named),
              ("env-cause, unnamed", unnamed_env)]
    for v in ("code-defect", "user-error", "unclear"):
        groups.append((v, [i for i in items if i["verdict"] == v]))
    rows = []
    for name, g in groups:
        a = [anything(i["o"]) for i in g]
        rows.append([name, len(g)] + [fmt(sum(x[k] for x in a), len(g)) for k in ("fact", "fact-nonos", "rule", "draft", "rule-or-draft", "any")])
    w(table(["group", "n", "any fact", "fact (not os, signature)", "any rule", "draft rule", "rule or draft", "anything"], rows))
    fk = Counter()
    for i in items:
        for k in {f[0] for f in i["o"]["facts"]}:
            fk[family(k)] += 1
    w("Fact keys printed (items with at least one fact on the key family): " + ", ".join("`%s` %d" % kv for kv in fk.most_common()) + ".\n")

    # measures
    w("## Tier B Measures on Env-Cause Items\n")
    w("Primary set: `env-cause` with a named attribute, a quote that verifies, and no rule that cites the thread. "
      "%d env-cause items, %d with a named attribute, %d quote-invalid (counted `unclear`), %d contaminated, %d primary. Wilson 95%% intervals."
      % (sum(1 for i in items if i["label_verdict"] == "env-cause"), len(named) + sum(1 for i in items if i["quote_invalid"] and i["label_verdict"] == "env-cause" and i["attribute"] != "unnamed"),
         sum(1 for i in items if i["quote_invalid"]), sum(1 for i in named if i["contaminated"]), len(primary)))
    w("")
    measures = ("fact", "fact-exact", "rule", "rule-exact", "named", "cause", "value")

    def block(label, g):
        return [label, len(g)] + [fmt(sum(1 for i in g if i["hits"][k]), len(g)) for k in measures]

    rows = [block("primary", primary), block("primary, attribute in Drift's key table", [i for i in primary if in_table(i["attribute"])]),
            block("primary, attribute outside the key table", [i for i in primary if not in_table(i["attribute"])]),
            block("named incl. contaminated (sensitivity)", named)]
    for s in ("large", "small"):
        rows.append(block("stratum " + s, [i for i in primary if stratum[i["repo"]] == s]))
    for e in sorted({eco[i["repo"]] for i in primary}):
        rows.append(block("ecosystem " + e, [i for i in primary if eco[i["repo"]] == e]))
    w(table(["set", "n"] + list(measures), rows))
    # mismatch control
    ordered = sorted(primary, key=lambda i: hashlib.sha256(i["id"].encode()).hexdigest())
    ctl = []
    for idx, i in enumerate(ordered):
        j = ordered[(idx + 1) % len(ordered)] if ordered else None
        if j and j["attribute"] != i["attribute"]:
            ctl.append(hits(i["o"], j["attribute"], None, None))
    w("Mismatch control (each item scored against another item's attribute, %d pairs): " % len(ctl)
      + "; ".join("%s %s" % (k, fmt(sum(1 for h in ctl if h[k]), len(ctl))) for k in ("fact", "rule", "named", "cause")) + ".\n")
    # null baseline
    w("## Null Baseline and Precision\n")
    w("Items with no named cause attribute (`code-defect`, `user-error`, `unclear`, unnamed env-cause). A rule or draft here is a coincidence with respect to a cause attribute.\n")
    rows = []
    for name, g in (("non-named items", others), ("code-defect", [i for i in items if i["verdict"] == "code-defect"]),
                    ("user-error", [i for i in items if i["verdict"] == "user-error"])):
        a = [anything(i["o"]) for i in g]
        rows.append([name, len(g)] + [fmt(sum(x[k] for x in a), len(g)) for k in ("fact", "rule", "draft", "rule-or-draft")])
    w(table(["set", "n", "any fact", "any rule", "draft rule", "rule or draft"], rows))
    resolved = [i for i in items if i["verdict"] != "unclear" and cause_set(i["o"])]
    good = [i for i in resolved if i["verdict"] == "env-cause" and i["attribute"] != "unnamed" and i["attribute"] in cause_set(i["o"])]
    w("Precision of `cause` (dev signal for bar 1): items with a non-empty cause set and a verdict other than `unclear`: %s; the cause set holds the labelled attribute in %s.\n"
      % (len(resolved), fmt(len(good), len(resolved))))
    w("Coverage of `cause` on env-cause items with a named attribute (primary): %s.\n" % fmt(sum(1 for i in primary if i["hits"]["cause"]), len(primary)))

    three_way(items, w)
    bars(items, primary, others, w)
    loop(args.half, items, rules, w)
    clean_loop(args.half, w)
    # per item
    w("## Env-Cause Items and the Gap\n")
    w("One row per `env-cause` dev item. Gap phrases: `hit`; `no extractor for it` (Drift prints no fact of that family on any dev item and it is not in the documented key table); "
      "`no rule for it` (a fact of the family is printed, the catalog has no rule on the key); `no rule for this change` (a fact of the key is printed and the catalog has rules on the key, none about this change); `wording unmatched` (the extractor exists, the issue text names the tool or the value, no fact printed); "
      "`sensor not captured (not in the issue text)` (neither the labelled value nor the tool name occurs in the issue title and body); `labeller gave no attribute`.\n")
    rows = []
    for i in sorted((i for i in items if i["label_verdict"] == "env-cause"), key=lambda i: i["id"]):
        o = i["o"]
        printed = []
        shown = sorted({"%s=%s" % (f[0], f[1][:24]) for f in o["facts"] if f[0] != "run:signature"})
        if shown:
            printed.append("facts " + ", ".join(shown[:6]))
        elif o["facts"]:
            printed.append("run:signature only")
        if o["rules"]:
            printed.append("rules " + ", ".join(o["rules"]))
        if o["draft"]:
            printed.append("draft on " + ", ".join(sorted(o["draft_keys"] - {"run:signature"})))
        g = gap(i, o, key_seen, rules) if i["verdict"] == "env-cause" else "quote not verified, counted unclear"
        flags = ("contaminated; " if i["contaminated"] else "")
        rows.append([i["id"], i["attribute"], "%s to %s" % (i["before"] or "?", i["after"] or "?") if i["before"] or i["after"] else "-",
                     "; ".join(printed) or "nothing", g, flags + DETAIL.get(i["id"], "")])
    w(table(["item", "attribute", "before to after", "Drift printed", "gap", "note"], rows))
    gc = Counter(r[4] for r in rows)
    w("Gap counts: " + ", ".join("%s %d" % kv for kv in gc.most_common()) + ".\n")

    hashes = []
    if only is None:
        tier_a(args, hd, rules, stratum, eco, items, w)
        for tier in ("b", "a"):
            for path in sorted(glob.glob(os.path.join(hd, "drift-out", tier, "*.json"))):
                hashes.append("%s  %s/%s" % (hashlib.sha256(open(path, "rb").read()).hexdigest(), tier, os.path.basename(path)))
        hash_path = os.path.join(os.path.dirname(args.out), "%s-output-hashes.txt" % args.half)
        open(hash_path, "w").write("\n".join(hashes) + "\n")
    w("## Reproduction\n")
    w("Drift outputs are `drift triage --json` over `title + blank line + body` of each item, produced by `lane-run.sh` inside lane `r2` (%d outputs, sha256 in `%s-output-hashes.txt`). "
      "Steps: `corpus_id.py drift-in --half %s --tier-a`; sync `runs/%s/drift-in` and `lane-run.sh` into the lane with `paisley-lane.sh sync`; "
      "`DRIFT=/work/bin/drift lane-run.sh runs/%s/drift-in/b runs/%s/drift-out/b` (and `/a`); copy `drift-out` back; `score.sh --half %s`.\n"
      % (len(hashes), args.half, args.half, args.half, args.half, args.half, args.half))
    w("## Limits\n")
    w("Dev half only. n is small: %d env-cause items with a named attribute, so every interval above is wide. Labels come from one model labeller per thread; the quote check is the only mechanical control. "
      "Only the first 100 comments of a thread were fetched. Tier A weak truth is a statement about the files a pull request touched. `upgrade-check` was not run. The gap phrases are rules over the output and the issue text, with a few hand-read notes marked as such.\n" % len(primary))
    open(args.out, "w", encoding="utf-8").write("\n".join(out))
    if only is None:
        json.dump([{k: v for k, v in i.items() if k not in ("o", "text")} for i in items],
                  open(os.path.join(hd, "score-items.json"), "w"), sort_keys=True, default=list)
    print("wrote", args.out)


if __name__ == "__main__":
    main()
