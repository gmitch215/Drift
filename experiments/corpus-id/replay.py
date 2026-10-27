"""Ask and reply loop for the named environment-cause items.

Stage 1 asks whether Drift's first answer holds a text fact for the labelled family or an ask
whose family is that family. Stage 2 answers the ask with a synthetic reply (a per-family template
filled with the label's failing-side value) through the real `drift why --reply` and asks whether
the session then states a fact for the family and whether a rule that reads the key matches.
Stage 2 measures ask coverage plus the parse round trip. It does not measure diagnosis: the reply
is made up from the label, not read from a machine.

Steps (the lane steps run inside a paisley lane with a linux drift executable):
  replay.py prepare --half dev              writes runs/<half>/replay/in and the manifest
  replay-lane.sh a runs/<half>/replay       drift why --json per item, writes the sessions
  replay.py reply --half dev                puts the labelled ask first and writes the replies
  replay-lane.sh b runs/<half>/replay       drift why --reply per item, keeps the sessions
  replay-lane.sh c runs/<half>/replay       drift why --reply --works per item (works-side reply)
  score.py reads out-a and out-b             the table "Ask and Reply Loop"

The clean-run loop (family `clean-run`) covers every dev item, in runs/<half>/replay-clean:
  replay.py prepare-clean --half dev        writes in/ and the manifest
  replay-lane.sh a runs/<half>/replay-clean drift why --json per item, writes the sessions
  replay.py clean-reply --half dev          writes a same-failure and a works reply per item that asks
  replay-lane.sh b runs/<half>/replay-clean same     drift why --reply per item, out-b-same
  replay-lane.sh b runs/<half>/replay-clean works    out-b-works
  score.py reads them                        the table "Clean Run Loop"
A same-failure reply is a hand-written template per signature kind filled with the failure
signature Drift extracted from the body (the `run:signature` fact). A works reply prints one line
without an error. Both measure ask coverage and the parse round trip, not diagnosis.
"""
import argparse
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_id as c  # noqa: E402

PLACEHOLDER = "1.2.3"
ALIASES = {"tool.powershell": "tool.pwsh"}
VERSION = re.compile(r"^\d+(\.\d+)*[A-Za-z0-9.+-]*$")

TEMPLATES = {
    "tool.node": "v{v}\n",
    "tool.npm": "{v}\n",
    "tool.pnpm": "{v}\n",
    "tool.yarn": "{v}\n",
    "tool.python": "Python {v}\n",
    "tool.pip": "pip {v} from /usr/lib/python3/dist-packages/pip (python 3.10)\n",
    "tool.php": "PHP {v} (cli) (built: Jan  1 2024 00:00:00) ( NTS )\nCopyright (c) The PHP Group\n",
    "tool.docker": "Docker version {v}, build abc1234\n",
    "tool.podman": "podman version {v}\n",
    "tool.skopeo": "skopeo version {v}\n",
    "tool.openssl": "OpenSSL {v} 15 Mar 2022 (Library: OpenSSL {v} 15 Mar 2022)\n",
    "tool.java": 'openjdk version "{v}" 2024-01-16\nOpenJDK Runtime Environment (build {v}+1)\n',
    "tool.git": "git version {v}\n",
    "tool.go": "go version go{v} linux/amd64\n",
    "tool.rust": "rustc {v} (abcdef123 2024-01-01)\n",
    "tool.ruby": "ruby {v} (2024-01-01 revision abc) [x86_64-linux]\n",
    "tool.gradle": "\n------------------------------------------------------------\nGradle {v}\n",
    "tool.dotnet": "{v}\n",
    "tool.electron": "v{v}\n",
    "tool.pwsh": "PowerShell {v}\n",
    "tool.curl": "curl {v} (x86_64-pc-linux-gnu) libcurl/{v} OpenSSL/3.0.2\n",
    "tool.paper": "This server is running Paper version git-Paper-196 (MC: {v}) (Implementing API version {v}-R0.1-SNAPSHOT)\n",
    "limits.nofile": "{v}\n",
    "limits.disk-quota": "Filesystem      Size  Used Avail Use% Mounted on\n/dev/sda1       100G   98G  2.0G  {v}% /\n",
    "limits.disk-space": "Filesystem      Size  Used Avail Use% Mounted on\n/dev/sda1       100G   98G  2.0G  {v}% /\n",
    "os.release": 'ID=ubuntu\nVERSION_ID="{v}"\n',
    "kernel.release": "{v}\n",
}
PERCENT = {"limits.disk-quota", "limits.disk-space"}


def norm(attribute):
    p = attribute.split(".")
    if len(p) > 1 and ".".join(p[:2]) in ALIASES:
        p = ALIASES[".".join(p[:2])].split(".") + p[2:]
    return ".".join(p)


def hit(family, attribute):
    a = norm(attribute)
    return a == family or a.startswith(family + ".")


def replay_dir(half):
    c.HALF = half
    return os.path.join(c.hdir(), "replay")


def named_items(half):
    out = []
    for f in sorted(glob.glob(os.path.join(c.HERE, "labels", "%s-*.yml" % half))):
        for r in c.parse_labels(open(f, encoding="utf-8").read()):
            if r["verdict"] == "env-cause" and r.get("attribute") not in (None, "unnamed"):
                out.append(r)
    return out


WORKS_PLACEHOLDER = "9.9.9"


def works_value(item, family):
    """The label's works-side value when it reads as a value of the family, else a placeholder."""
    before = item.get("before")
    if before and family not in PERCENT and VERSION.match(str(before)):
        return str(before), False
    return ("10" if family in PERCENT else WORKS_PLACEHOLDER), True


def failing_value(item, family):
    """The label's failing-side value when it reads as a value of the family, else a placeholder."""
    after = item.get("after")
    if after and family in PERCENT:
        return PLACEHOLDER, True
    if after and VERSION.match(str(after)):
        return str(after), False
    return PLACEHOLDER, True


def prepare(args):
    d = replay_dir(args.half)
    os.makedirs(os.path.join(d, "in"), exist_ok=True)
    manifest = {}
    for item in named_items(args.half):
        repo, num = item["id"].rsplit("#", 1)
        name = "%s__%s" % (c.slug(repo), num)
        src = os.path.join(c.hdir(), "drift-in", "b", name + ".md")
        open(os.path.join(d, "in", name + ".md"), "w", encoding="utf-8").write(open(src, encoding="utf-8").read())
        manifest[name] = {"id": item["id"], "attribute": item["attribute"], "after": item.get("after"),
                          "before": item.get("before")}
    json.dump(manifest, open(os.path.join(d, "manifest.json"), "w"), indent=1, sort_keys=True)
    print("prepared %d items in %s" % (len(manifest), d))


def reply(args):
    d = replay_dir(args.half)
    manifest = json.load(open(os.path.join(d, "manifest.json")))
    os.makedirs(os.path.join(d, "sessions"), exist_ok=True)
    os.makedirs(os.path.join(d, "reply"), exist_ok=True)
    made = 0
    for name, m in sorted(manifest.items()):
        path = os.path.join(d, "out-a", name + ".session.json")
        if not os.path.exists(path):
            continue
        session = json.load(open(path))
        asks = session["asks"]
        want = [a for a in asks if hit(a["family"], m["attribute"])]
        template = TEMPLATES.get(want[0]["family"]) if want else None
        if not want or template is None:
            continue
        value, placeholder = failing_value(m, want[0]["family"])
        session["asks"] = want[:1] + [a for a in asks if a is not want[0]]
        json.dump(session, open(os.path.join(d, "sessions", name + ".json"), "w"), sort_keys=True, separators=(",", ":"))
        open(os.path.join(d, "reply", name + ".txt"), "w").write(template.format(v=value))
        there, there_placeholder = works_value(m, want[0]["family"])
        open(os.path.join(d, "reply", name + ".works.txt"), "w").write(template.format(v=there))
        m["works_placeholder"] = there_placeholder
        m["placeholder"] = placeholder
        made += 1
    json.dump(manifest, open(os.path.join(d, "manifest.json"), "w"), indent=1, sort_keys=True)
    print("wrote %d replies" % made)


SAME_TEMPLATES = {
    "npm": "{sig}\nnpm ERR! A complete log of this run can be found in: /root/.npm/_logs/run.log\n",
    "exception": "{sig}\n    at <anonymous> (<stdin>:1:1)\n",
    "line": "{sig}\n",
}
WORKS_REPLY = "ok\n"
PROMPT = re.compile(r"^\s*[$>%] \S|\b(run|ran|running|executing|typing)\b[^`\n]{0,40}`[^`\n]+`|command:\s*`", re.M | re.I)
CLEAN_VERDICTS = ("env-cause", "code-defect", "user-error", "unclear")


def signature_kind(sig):
    """The template kind of a failure signature: an npm error, a `SomethingError: ...` line or any other line."""
    if sig.startswith("npm ERR!"):
        return "npm"
    if re.match(r"^[\w.$]+(Error|Exception)\b", sig):
        return "exception"
    return "line"


def clean_dir(half):
    c.HALF = half
    return os.path.join(c.hdir(), "replay-clean")


def labelled_items(half, verdicts):
    out = []
    for f in sorted(glob.glob(os.path.join(c.HERE, "labels", "%s-*.yml" % half))):
        for r in c.parse_labels(open(f, encoding="utf-8").read()):
            if r["verdict"] in verdicts:
                out.append(r)
    return out


def prepare_clean(args):
    d = clean_dir(args.half)
    os.makedirs(os.path.join(d, "in"), exist_ok=True)
    manifest = {}
    for item in labelled_items(args.half, CLEAN_VERDICTS):
        repo, num = item["id"].rsplit("#", 1)
        name = "%s__%s" % (c.slug(repo), num)
        src = os.path.join(c.hdir(), "drift-in", "b", name + ".md")
        if not os.path.exists(src):
            continue
        open(os.path.join(d, "in", name + ".md"), "w", encoding="utf-8").write(open(src, encoding="utf-8").read())
        manifest[name] = {"id": item["id"], "verdict": item["verdict"]}
    json.dump(manifest, open(os.path.join(d, "manifest.json"), "w"), indent=1, sort_keys=True)
    print("prepared %d items in %s" % (len(manifest), d))


def signature_of(out):
    for f in out.get("facts", []):
        if f["key"] == "run:signature" and f["side"] == "failing":
            return f["value"]
    return None


def clean_reply(args):
    d = clean_dir(args.half)
    manifest = json.load(open(os.path.join(d, "manifest.json")))
    for sub in ("sessions", "reply"):
        os.makedirs(os.path.join(d, sub), exist_ok=True)
    asked = built = 0
    for name, m in sorted(manifest.items()):
        out = os.path.join(d, "out-a", name + ".json")
        path = os.path.join(d, "out-a", name + ".session.json")
        if not os.path.exists(out) or not os.path.exists(path):
            continue
        session = json.load(open(path))
        asks = session["asks"]
        want = [a for a in asks if a["family"] == "clean-run"]
        if not want:
            continue
        asked += 1
        session["asks"] = want[:1] + [a for a in asks if a is not want[0]]
        json.dump(session, open(os.path.join(d, "sessions", name + ".json"), "w"), sort_keys=True, separators=(",", ":"))
        open(os.path.join(d, "reply", name + ".works.txt"), "w").write(WORKS_REPLY)
        sig = signature_of(json.load(open(out)))
        m["signature"] = sig is not None
        if sig is not None:
            m["kind"] = signature_kind(sig)
            open(os.path.join(d, "reply", name + ".same.txt"), "w").write(SAME_TEMPLATES[m["kind"]].format(sig=sig))
            built += 1
    json.dump(manifest, open(os.path.join(d, "manifest.json"), "w"), indent=1, sort_keys=True)
    print("%d items ask for the clean run, %d same-failure replies built" % (asked, built))


def load_clean(half):
    """Per item: the first output, the output after each reply (or None) and the manifest row."""
    d = clean_dir(half)
    path = os.path.join(d, "manifest.json")
    if not os.path.exists(path) or not os.path.isdir(os.path.join(d, "out-a")):
        return None
    out = {}
    for name, m in json.load(open(path)).items():
        a = os.path.join(d, "out-a", name + ".json")
        if not os.path.exists(a):
            continue
        text = open(os.path.join(d, "in", name + ".md"), encoding="utf-8").read()
        row = {"m": m, "a": json.load(open(a)), "prompt": bool(PROMPT.search(text))}
        for variant in ("same", "works"):
            b = os.path.join(d, "out-b-" + variant, name + ".json")
            row[variant] = read_output(b)
        out[m["id"]] = row
    return out


def read_output(path):
    """The JSON a reply run printed, or None when it printed none (the reply was refused)."""
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return None
    return json.load(open(path))


def load(half):
    """Per item: the first output, the output after the reply (or None) and the manifest row."""
    d = replay_dir(half)
    path = os.path.join(d, "manifest.json")
    if not os.path.exists(path) or not os.path.isdir(os.path.join(d, "out-a")):
        return None
    out = {}
    for name, m in json.load(open(path)).items():
        a = os.path.join(d, "out-a", name + ".json")
        b = os.path.join(d, "out-b", name + ".json")
        if not os.path.exists(a):
            continue
        c_path = os.path.join(d, "out-c", name + ".json")
        out[m["id"]] = {"m": m, "a": json.load(open(a)), "b": read_output(b),
                        "c": read_output(c_path)}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=("prepare", "reply", "prepare-clean", "clean-reply"))
    ap.add_argument("--half", default="dev", choices=("dev", "heldout"))
    args = ap.parse_args()
    {"prepare": prepare, "reply": reply, "prepare-clean": prepare_clean, "clean-reply": clean_reply}[args.step](args)


if __name__ == "__main__":
    main()
