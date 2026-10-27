import argparse
import fnmatch
import hashlib
import hmac
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BASE = os.environ.get("RUNS", os.path.join(HERE, "runs"))
HALF = "heldout"
SEED = "drift-triage-split-v1"
TOTAL = 1500
BUDGET = int(os.environ.get("GH_BUDGET", "2800"))
MAINT = ("MEMBER", "OWNER", "COLLABORATOR")
BATCH_SIZE = 20
ISSUE_WORDS = 800
THREAD_WORDS = 2500
COMMENT_CAPS = (400, 250, 150, 100, 60)

PHRASES = [
    r"caused by",
    r"due to",
    r"because of",
    r"regression",
    r"since (version|v?[0-9])",
    r"after (upgrading|updating)",
    r"downgrad",
    r"pin(ned)? to",
    r"fixed in",
    r"introduced in",
    r"broke in|broken in",
    r"work-?around",
    r"requires? (version|v?[0-9])",
    r"not supported (on|in)",
    r"(upgrade|update) to",
    r"(older|outdated|newer) version",
    r"(only )?works (on|with|in) (version|v?[0-9])",
]
PHRASE_RE = re.compile("|".join("(?:%s)" % p for p in PHRASES), re.IGNORECASE)


#region manifest and ordering


def manifest():
    text = open(os.path.join(ROOT, "triage", "corpus.yml"), encoding="utf-8").read()
    head = {}
    for key in ("cutoff", "cap", "salt"):
        head[key] = re.search(r"^%s: (.+)$" % key, text, re.M).group(1).strip()
    repos = []
    for block in re.split(r"^  - repo: ", text, flags=re.M)[1:]:
        lines = block.splitlines()
        row = {"repo": lines[0].strip()}
        for line in lines[1:]:
            m = re.match(r"    (host|stratum|form_policy): (.+)$", line)
            if m:
                row[m.group(1)] = m.group(2).strip()
        repos.append(row)
    head["repos"] = repos
    return head


def strata():
    out = {}
    for line in open(os.path.join(HERE, "strata.yml"), encoding="utf-8"):
        if line.strip():
            repo, eco = line.rstrip("\n").split(": ")
            out[repo] = eco
    return out


def github_repos(m):
    return [r for r in m["repos"] if r["host"] == "github"]


def per_repo_k(m):
    return TOTAL // len(github_repos(m))


def hdir():
    return os.path.join(BASE, HALF)


def in_half(repo, number):
    return is_dev(repo, number) == (HALF == "dev")


def is_dev(repo, number):
    digest = hashlib.sha256(("%s:%s#%d" % (SEED, repo, number)).encode()).digest()
    return digest[0] < 128


def rank(repo, number):
    return hashlib.sha256(("%s#%d" % (repo, number)).encode()).hexdigest()


def ordered_half(repo, lo, hi):
    nums = [n for n in range(lo, hi + 1) if in_half(repo, n)]
    return sorted(nums, key=lambda n: rank(repo, n))


def wilson(k, n, z=1.959964):
    if n == 0:
        return (float("nan"), float("nan"))
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    s = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - s) / d, (c + s) / d)


#endregion

#region text rules

FENCE_RE = re.compile(r"(```|~~~).*?(\1|\Z)", re.DOTALL)


def strip_for_filter(text):
    text = FENCE_RE.sub(" ", text or "")
    return "\n".join(l for l in text.split("\n") if not l.lstrip().startswith(">"))


def phrase_hits(text):
    return sorted({m.group(0).lower() for m in PHRASE_RE.finditer(text)})


def is_maintainer(comment, author):
    return (
        comment.get("assoc") in MAINT
        and comment.get("type") != "Bot"
        and comment.get("login") is not None
        and comment.get("login") != author
    )


def thread_hits(thread):
    hits = []
    author = (thread["author"] or {}).get("login")
    for c in thread["comments"]:
        if is_maintainer(c, author):
            found = phrase_hits(strip_for_filter(c["body"]))
            if found:
                hits.append({"source": c["id"], "phrases": found})
    title = thread.get("closing", {}).get("title")
    if title:
        found = phrase_hits(title)
        if found:
            hits.append({"source": "closing", "phrases": found})
    return hits


TOKEN_RES = [
    re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"),
    re.compile(r"(?i)\b(token|secret|password|api[_-]?key)(\s*[:=]\s*)[^\s'\"]{12,}"),
]
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
MENTION_RE = re.compile(r"(?<![\w/.@-])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))(?![\w/-])")


def user_hash(salt, login):
    mac = hmac.new(salt.encode(), login.strip().lstrip("@").lower().encode(), hashlib.sha256)
    return "u" + mac.hexdigest()[:12]


def redact(text, salt):
    out = []
    fenced = False
    for line in (text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        t = line.strip()
        toggles = t.startswith("```") or t.startswith("~~~")
        if toggles:
            fenced = not fenced
        if not fenced and not toggles:
            line = MENTION_RE.sub(lambda m: "@" + user_hash(salt, m.group(1)), line)
        line = EMAIL_RE.sub("<email>", line)
        for i, r in enumerate(TOKEN_RES):
            line = r.sub("[redacted]" if i < 3 else r"\1\2[redacted]", line)
        out.append(line)
    return "\n".join(out)


def words(text):
    return len(text.split())


def cut(text, cap):
    toks = re.findall(r"\S+|\s+", text)
    total = sum(1 for t in toks if not t.isspace())
    if total <= cap:
        return text
    head, tail = math.ceil(cap * 0.7), cap - math.ceil(cap * 0.7)
    out, n = [], 0
    for t in toks:
        if not t.isspace():
            n += 1
        if n > head:
            break
        out.append(t)
    rest, n = [], 0
    for t in reversed(toks):
        if not t.isspace():
            n += 1
        if n > tail:
            break
        rest.append(t)
    marker = "\n[... %d words cut ...]\n" % (total - head - tail)
    return "".join(out).rstrip() + marker + "".join(reversed(rest)).lstrip()


def trim(thread, salt):
    """Returns (issue_body, comments, note); comments are dicts with role, hash, id, date, text."""
    author = (thread["author"] or {}).get("login")
    body = redact(thread["body"], salt)
    body_cut = cut(body, ISSUE_WORDS)
    keep, dropped_other = [], 0
    for c in thread["comments"]:
        if c.get("login") is None:
            dropped_other += 1
            continue
        if is_maintainer(c, author):
            role = "MAINT"
        elif c["login"] == author:
            role = "AUTHOR"
        else:
            dropped_other += 1
            continue
        keep.append(
            {
                "role": role,
                "hash": user_hash(salt, c["login"]),
                "id": c["id"],
                "date": c["created_at"][:10],
                "full": redact(c["body"], salt),
            }
        )
    dropped = []

    def fits(cap):
        size = words(body_cut)
        for c in keep:
            w = words(c["full"])
            size += w if w <= cap else cap + 5
        return size <= THREAD_WORDS

    while True:
        cap = next((c for c in COMMENT_CAPS if fits(c)), None)
        if cap is not None or not keep:
            break
        authors = [c for c in keep if c["role"] == "AUTHOR"]
        victim = authors[-1] if authors else keep[0]
        keep.remove(victim)
        dropped.append(victim["role"])
    if cap is None:
        cap = COMMENT_CAPS[-1]
    for c in keep:
        c["text"] = cut(c["full"], cap)
    note = "issue body %d to %d words; comment cap %d words; %d other-user comments dropped" % (
        words(body),
        words(body_cut),
        cap,
        dropped_other,
    )
    if dropped:
        note += "; %d %s comments dropped to fit %d words" % (
            len(dropped),
            "/".join(sorted(set(dropped))),
            THREAD_WORDS,
        )
    return body_cut, keep, note


#endregion

#region gh


class NotFound(Exception):
    pass


class Gh:
    def __init__(self, runs):
        self.log = os.path.join(runs, "requests.log")
        os.makedirs(runs, exist_ok=True)
        try:
            stamps = [float(x) for x in open(self.log).read().split()]
        except FileNotFoundError:
            stamps = []
        now = time.time()
        self.stamps = [s for s in stamps if now - s < 3600]
        with open(self.log, "w") as f:
            f.write("".join("%.0f\n" % s for s in self.stamps))
        self.count = 0
        self.last_search = 0.0

    def _throttle(self):
        now = time.time()
        self.stamps = [s for s in self.stamps if now - s < 3600]
        if len(self.stamps) >= BUDGET:
            wait = self.stamps[0] + 3601 - now
            say("budget %d/h reached, sleeping %.0f s" % (BUDGET, wait))
            time.sleep(max(wait, 1))
        self.stamps.append(time.time())
        with open(self.log, "a") as f:
            f.write("%.0f\n" % self.stamps[-1])
        self.count += 1
        if self.count % 100 == 0:
            self._limit_check()

    def _limit_check(self):
        out = subprocess.run(
            ["gh", "api", "rate_limit", "--jq", ".resources.core | [.remaining,.reset] | @tsv"],
            capture_output=True,
            text=True,
        )
        if out.returncode == 0:
            remaining, reset = (int(x) for x in out.stdout.split())
            if remaining < 200:
                wait = reset - time.time() + 5
                say("core remaining %d, sleeping %.0f s" % (remaining, wait))
                time.sleep(max(wait, 1))

    def api(self, path, search=False):
        if search:
            gap = 2.3 - (time.time() - self.last_search)
            if gap > 0:
                time.sleep(gap)
        for attempt in range(8):
            self._throttle()
            out = subprocess.run(["gh", "api", path], capture_output=True, text=True)
            if search:
                self.last_search = time.time()
            if out.returncode == 0:
                return json.loads(out.stdout)
            err = out.stderr + out.stdout
            if "HTTP 404" in err or "HTTP 410" in err:
                raise NotFound(path)
            wait = 30 * (attempt + 1)
            if "rate limit" in err.lower() or "HTTP 403" in err or "HTTP 429" in err:
                wait = 90 * (attempt + 1)
            say("gh error on %s (%s), retry in %d s" % (path.split("?")[0], err.strip()[:80], wait))
            time.sleep(wait)
        raise RuntimeError("gh failed 8 times on " + path.split("?")[0])


def say(msg):
    print(datetime.now(timezone.utc).strftime("%H:%M:%S"), msg, flush=True)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
    with os.fdopen(fd, "w") as f:
        json.dump(obj, f, sort_keys=True)
    os.replace(tmp, path)


def read_json(path, default=None):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def slug(repo):
    return repo.replace("/", "__")


#endregion

#region fetch


def search_count(gh, q):
    return gh.api("search/issues?per_page=1&q=" + quote(q), search=True)["total_count"]


def window_from(gh, repo, cutoff, cap):
    """Creation time of the cap-th newest issue created on or before the cutoff, or None."""
    base = "repo:%s is:issue " % repo
    if search_count(gh, base + "created:<=%s" % cutoff) <= cap:
        return None
    lo = int(datetime(2008, 1, 1, tzinfo=timezone.utc).timestamp())
    hi = int(datetime.fromisoformat(cutoff + "T23:59:59+00:00").timestamp())

    def iso(t):
        return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    end = iso(hi)
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if search_count(gh, base + "created:%s..%s" % (iso(mid), end)) >= cap:
            lo = mid
        else:
            hi = mid
    return iso(lo)


def created_of(gh, repo, n):
    for k in range(6):
        try:
            return gh.api("repos/%s/issues/%d" % (repo, n + k))["created_at"], n + k
        except NotFound:
            continue
    return None, n


def start_number(gh, repo, window, top):
    if window is None:
        return 1
    lo, hi = 1, top
    while hi - lo > 1:
        mid = (lo + hi) // 2
        created, _ = created_of(gh, repo, mid)
        if created is not None and created >= window:
            hi = mid
        else:
            lo = mid
    return max(1, lo - 50)


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def repo_meta(gh, m, repo):
    path = os.path.join(BASE, "meta", slug(repo) + ".json")
    meta = read_json(path)
    if meta is None:
        newest = gh.api("repos/%s/issues?state=all&per_page=1&sort=created&direction=desc" % repo)
        top = newest[0]["number"]
        window = None if top < int(m["cap"]) else window_from(gh, repo, m["cutoff"], int(m["cap"]))
        meta = {"top": top, "window": window}
        write_json(path, meta)
    return meta


THREAD_FIELDS = (
    "number state stateReason createdAt closedAt title body authorAssociation author{login} "
    "labels(first:20){nodes{name}} "
    "comments(first:100){totalCount nodes{databaseId createdAt body authorAssociation "
    "author{login __typename}}} "
    "timelineItems(last:1,itemTypes:[CLOSED_EVENT]){nodes{... on ClosedEvent{closer{__typename "
    "... on PullRequest{number title} ... on Commit{messageHeadline}}}}}"
)
BATCH_QUERY = (
    "query($owner:String!,$name:String!){rateLimit{cost remaining resetAt}"
    "repository(owner:$owner,name:$name){%s}}"
)
THREADS_PER_CALL = 25


def thread_from_node(repo, r):
    a = r.get("author") or {}
    thread = {
        "repo": repo,
        "number": r["number"],
        "title": r["title"],
        "body": r.get("body") or "",
        "state": r["state"].lower(),
        "state_reason": (r.get("stateReason") or "").lower() or None,
        "labels": [l["name"] for l in r["labels"]["nodes"]],
        "created_at": r["createdAt"],
        "closed_at": r.get("closedAt"),
        "author": {"login": a.get("login"), "assoc": r.get("authorAssociation")},
        "comment_count": r["comments"]["totalCount"],
        "rank": rank(repo, r["number"]),
        "comments": [],
        "closing": {},
    }
    for c in r["comments"]["nodes"]:
        u = c.get("author") or {}
        thread["comments"].append(
            {
                "id": c["databaseId"],
                "login": u.get("login"),
                "type": u.get("__typename"),
                "assoc": c.get("authorAssociation"),
                "created_at": c["createdAt"],
                "body": c.get("body") or "",
            }
        )
    for e in r["timelineItems"]["nodes"]:
        closer = (e or {}).get("closer")
        if closer and closer["__typename"] == "PullRequest":
            thread["closing"] = {"kind": "pr", "title": closer["title"], "number": closer["number"]}
        elif closer and closer["__typename"] == "Commit":
            thread["closing"] = {"kind": "commit", "title": closer["messageHeadline"]}
    return thread


def judge(r, st, cutoff):
    """Why a fetched issue node is skipped, or None when it may be eligible."""
    if r is None:
        return "not-an-issue"
    if r["state"] != "CLOSED":
        return "open"
    if r["createdAt"][:10] > cutoff:
        return "after-cutoff"
    if st["window"] and r["createdAt"] < st["window"]:
        return "outside-window"
    if not r["comments"]["totalCount"]:
        return "no-comments"
    return None


def fetch_repo(gh, gql, m, row, runs, force_k=None):
    repo = row["repo"]
    owner, name = repo.split("/", 1)
    k = force_k or per_repo_k(m)
    state_path = os.path.join(runs, "state", slug(repo) + ".json")
    st = read_json(state_path) or {"visited": {}, "eligible": [], "done": False}
    if st["done"]:
        return st
    cutoff = m["cutoff"]
    if "top" not in st:
        meta = repo_meta(gh, m, repo)
        st.update({"top": meta["top"], "window": meta["window"]})
        write_json(state_path, st)
    if "n0" not in st:
        st["n0"] = start_number(gh, repo, st["window"], st["top"])
        write_json(state_path, st)
    order = ordered_half(repo, st["n0"], st["top"])
    st["order_len"] = len(order)
    todo = [n for n in order if str(n) not in st["visited"]]
    for i in range(0, len(todo), THREADS_PER_CALL):
        if len(st["eligible"]) >= k:
            break
        chunk = todo[i : i + THREADS_PER_CALL]
        body = " ".join("i%d:issue(number:%d){%s}" % (n, n, THREAD_FIELDS) for n in chunk)
        data = gql.call(BATCH_QUERY % body, owner=owner, name=name)["data"]["repository"]
        for n in chunk:
            if len(st["eligible"]) >= k:
                break
            r = data.get("i%d" % n)
            reason = judge(r, st, cutoff)
            if reason is None:
                thread = thread_from_node(repo, r)
                author = thread["author"]["login"]
                if any(is_maintainer(c, author) for c in thread["comments"]):
                    write_json(os.path.join(runs, "threads", slug(repo), "%d.json" % n), thread)
                    reason = "eligible"
                    st["eligible"].append(n)
                else:
                    reason = "no-maintainer-comment"
            st["visited"][str(n)] = reason
        write_json(state_path, st)
        say("%s: %d/%d eligible, %d visited of %d" % (repo, len(st["eligible"]), k, len(st["visited"]), len(order)))
    st["done"] = True
    write_json(state_path, st)
    say("%s: done, %d eligible, %d visited of %d candidates" % (repo, len(st["eligible"]), len(st["visited"]), len(order)))
    return st


def cmd_fetch(args):
    m = manifest()
    gh = Gh(BASE)
    gql = Gql(BASE)
    for row in github_repos(m):
        if args.repo and row["repo"] != args.repo:
            continue
        fetch_repo(gh, gql, m, row, hdir(), args.k)


def cmd_sample(args):
    m = manifest()
    st = read_json(os.path.join(BASE, "meta", slug(args.repo) + ".json"))
    lo = 1
    hi = (st or {}).get("top") or args.top
    if not hi:
        sys.exit("no state for %s; pass --top <newest issue number>" % args.repo)
    for n in ordered_half(args.repo, lo, hi)[: args.first]:
        print(n, rank(args.repo, n))


#endregion

#region filter and batch


def load_threads(runs):
    base = os.path.join(runs, "threads")
    for d in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        for f in sorted(os.listdir(os.path.join(base, d)), key=lambda x: int(x.split(".")[0])):
            yield read_json(os.path.join(base, d, f))


def cmd_filter(args):
    m = manifest()
    kept, per = [], {}
    for t in load_threads(hdir()):
        row = per.setdefault(t["repo"], [0, 0])
        row[0] += 1
        hits = thread_hits(t)
        if hits:
            row[1] += 1
            kept.append({"repo": t["repo"], "number": t["number"], "hits": hits})
    kept.sort(key=lambda k: rank(k["repo"], k["number"]))
    write_json(os.path.join(hdir(), "kept.json"), kept)
    lines = ["repo fetched passed"]
    for repo in sorted(per):
        lines.append("%s %d %d" % (repo, per[repo][0], per[repo][1]))
    lines.append("total %d %d" % (sum(v[0] for v in per.values()), sum(v[1] for v in per.values())))
    text = "\n".join(lines) + "\n"
    open(os.path.join(hdir(), "filter-summary.txt"), "w").write(text)
    print(text, end="")


def render_thread(idx, t, salt):
    body, comments, note = trim(t, salt)
    author = (t["author"] or {}).get("login")
    closing = t.get("closing") or {}
    ref = "none"
    if closing:
        ref = '%s title: "%s"' % ("pull request" if closing["kind"] == "pr" else "commit", redact(closing["title"], salt))
    out = [
        "## %02d. %s#%d" % (idx, t["repo"], t["number"]),
        "",
        "labels: %s | opened %s | closed %s (%s) | author %s" % (
            ", ".join(t["labels"]) or "none",
            t["created_at"][:10],
            (t["closed_at"] or "?")[:10],
            t["state_reason"] or "no reason",
            user_hash(salt, author) if author else "deleted",
        ),
        "closing reference: " + ref,
        "trim: " + note,
        "",
        "### Issue",
        "",
        "Title: " + redact(t["title"], salt),
        "",
        body,
        "",
    ]
    for c in comments:
        out += ["### Comment %s [%s %s] %s" % (c["id"], c["role"], c["hash"], c["date"]), "", c["text"], ""]
    return "\n".join(out)


def cmd_batch(args):
    m = manifest()
    salt = m["salt"]
    kept = read_json(os.path.join(hdir(), "kept.json"))
    kept.sort(key=lambda k: rank(k["repo"], k["number"]))
    bdir = os.path.join(HERE, "batches")
    os.makedirs(bdir, exist_ok=True)
    for i in range(0, len(kept), BATCH_SIZE):
        chunk = kept[i : i + BATCH_SIZE]
        parts = []
        for j, k in enumerate(chunk, 1):
            t = read_json(os.path.join(hdir(), "threads", slug(k["repo"]), "%d.json" % k["number"]))
            parts.append(render_thread(j, t, salt))
        name = "%s-%02d.md" % (HALF, i // BATCH_SIZE + 1)
        open(os.path.join(bdir, name), "w", encoding="utf-8").write("# %s\n\n" % name[:-3] + "\n---\n\n".join(parts))
    print("%d threads in %d batches" % (len(kept), math.ceil(len(kept) / BATCH_SIZE)))


#endregion

#region labels

VERDICTS = ("env-cause", "code-defect", "user-error", "unclear")
LABEL_KEYS = ("id", "verdict", "attribute", "before", "after", "comment", "quote", "note")
QUOTE_WORDS = 40


def parse_labels(text):
    """The label YAML subset: a list of flat mappings with JSON scalar values or bare words."""
    rows, cur = [], None
    for n, line in enumerate(text.split("\n"), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("- "):
            cur = {}
            rows.append(cur)
            line = "  " + line[2:]
        if cur is None or not line.startswith("  ") or ": " not in line + " ":
            raise ValueError("line %d: not a label mapping" % n)
        key, _, raw = line.strip().partition(":")
        raw = raw.strip()
        try:
            value = json.loads(raw) if raw[:1] in '"-0123456789' or raw in ("null", "true", "false") else raw
        except ValueError:
            raise ValueError("line %d: bad value %r" % (n, raw[:40]))
        cur[key.strip()] = value
    return rows


def norm_ws(text):
    return " ".join((text or "").split())


def batch_threads(runs, m, index):
    kept = read_json(os.path.join(runs, "kept.json"))
    kept.sort(key=lambda k: rank(k["repo"], k["number"]))
    chunk = kept[(index - 1) * BATCH_SIZE : index * BATCH_SIZE]
    return [
        read_json(os.path.join(runs, "threads", slug(k["repo"]), "%d.json" % k["number"])) for k in chunk
    ]


def check_label(row, thread, salt):
    """Problems with one label row against its thread; an empty list means valid."""
    bad = []
    extra = set(row) - set(LABEL_KEYS)
    if extra:
        bad.append("unknown keys %s" % sorted(extra))
    verdict = row.get("verdict")
    if verdict not in VERDICTS:
        return bad + ["verdict %r" % verdict]
    attr = row.get("attribute")
    if verdict == "env-cause":
        if not isinstance(attr, str) or not re.match(r"^(unnamed|[a-z][A-Za-z0-9_.:-]*)$", attr):
            bad.append("env-cause needs an attribute key or unnamed")
    elif attr is not None:
        bad.append("attribute must be null unless env-cause")
    quote, cid = row.get("quote"), row.get("comment")
    if verdict == "unclear" and quote in (None, ""):
        return bad
    if not quote or cid is None:
        return bad + ["quote and comment are required"]
    if words(quote) > QUOTE_WORDS:
        bad.append("quote over %d words" % QUOTE_WORDS)
    if cid == "closing":
        pool = redact((thread.get("closing") or {}).get("title", ""), salt)
    elif cid == "issue":
        pool = redact(thread["title"] + "\n" + thread["body"], salt)
    else:
        found = [c for c in thread["comments"] if c["id"] == cid]
        pool = redact(found[0]["body"], salt) if found else None
        if pool is None:
            return bad + ["comment id %r not in thread" % cid]
    if norm_ws(quote) not in norm_ws(pool):
        bad.append("quote not found verbatim in %s" % cid)
    return bad


def cmd_labels(args):
    m = manifest()
    problems = 0
    for index in args.batch:
        path = os.path.join(HERE, "labels", "%s-%02d.yml" % (HALF, index))
        threads = batch_threads(hdir(), m, index)
        try:
            rows = parse_labels(open(path, encoding="utf-8").read())
        except (OSError, ValueError) as e:
            print("%s-%02d: %s" % (HALF, index, e))
            problems += 1
            continue
        ids = ["%s#%d" % (t["repo"], t["number"]) for t in threads]
        by_id = {r.get("id"): r for r in rows}
        short = args.partial and set(by_id) <= set(ids) and len(rows) == len(by_id)
        if not short and (sorted(by_id) != sorted(ids) or len(rows) != len(ids)):
            print("%s-%02d: ids differ from the batch (missing %s, extra %s)" % (
                HALF, index, sorted(set(ids) - set(by_id)), sorted(set(by_id) - set(ids))))
            problems += 1
        for t in threads:
            tid = "%s#%d" % (t["repo"], t["number"])
            if tid in by_id:
                for p in check_label(by_id[tid], t, m["salt"]):
                    print("%s-%02d %s: %s" % (HALF, index, tid, p))
                    problems += 1
    print("labels: %d problems" % problems)
    sys.exit(1 if problems else 0)


#endregion

#region tier a

GQL_BUDGET = int(os.environ.get("GQL_BUDGET", "3000"))
TIER_A_Q = 600
FILES_PER_CALL = 25
MAX_ATTR_FILES = 5
MAX_PR_FILES = 30

LIST_QUERY = (
    "query($owner:String!,$name:String!,$cursor:String){"
    "rateLimit{cost remaining resetAt}"
    "repository(owner:$owner,name:$name){"
    "issues(first:100,after:$cursor,states:CLOSED,orderBy:{field:CREATED_AT,direction:DESC}){"
    "pageInfo{hasNextPage endCursor}"
    "nodes{number createdAt closedAt title body author{login}"
    "closedByPullRequestsReferences(first:2){nodes{number title merged}}}}}}"
)

DEPENDENCY_FILES = (
    "package.json package-lock.json npm-shrinkwrap.json yarn.lock pnpm-lock.yaml bun.lock "
    "bun.lockb requirements*.txt constraints*.txt Pipfile Pipfile.lock poetry.lock "
    "pyproject.toml uv.lock Cargo.toml Cargo.lock go.mod go.sum Gemfile Gemfile.lock "
    "composer.json composer.lock pom.xml build.gradle build.gradle.kts settings.gradle "
    "settings.gradle.kts gradle.properties libs.versions.toml gradle-wrapper.properties "
    "*.csproj Directory.Packages.props Directory.Build.props global.json packages.config "
    "NuGet.config"
).split()
VERSION_FILES = (
    ".nvmrc .node-version .tool-versions .python-version .ruby-version .java-version "
    ".go-version .sdkmanrc rust-toolchain rust-toolchain.toml"
).split()
CONTAINER_FILES = "Dockerfile* *.dockerfile docker-compose*.yml compose*.yml".split()
CI_FILES = ".gitlab-ci.yml .travis.yml azure-pipelines*.yml appveyor.yml Jenkinsfile".split()
CONFIG_FILES = (
    ".editorconfig tsconfig*.json .eslintrc* .prettierrc* *.config.js *.config.ts "
    "*.config.mjs *.config.cjs .babelrc tox.ini setup.cfg pytest.ini .npmrc *.properties "
    ".gitattributes"
).split()
BASENAME_PATTERNS = DEPENDENCY_FILES + VERSION_FILES + CONTAINER_FILES + CI_FILES + CONFIG_FILES


def is_env_config_path(path):
    if path.startswith(".github/workflows/") or path.startswith(".github/actions/"):
        return True
    if path == ".circleci/config.yml":
        return True
    base = path.rsplit("/", 1)[-1]
    return any(fnmatch.fnmatchcase(base, pat) for pat in BASENAME_PATTERNS)


def is_docs_path(path):
    return path.lower().endswith(".md") or path.startswith("docs/")


def classify(paths, total):
    """Weak truth class of a closing pull request from its changed paths."""
    if paths and total <= MAX_PR_FILES and all(is_env_config_path(p) for p in paths):
        return "env-or-config-change"
    return "code-change"


TOOL_NAMES = {"nodejs": "node", "golang": "go"}
NPM_SKIP = {"version", "name", "lockfileVersion", "resolved", "integrity", "requires"}
WORKFLOW_RE = re.compile(r"^\s*-?\s*(node|python|java|go|dotnet|php|ruby)-version\s*:")


def derive_keys(path, added):
    """Attribute keys named by the added lines of one changed file, by fixed parsers."""
    base = path.rsplit("/", 1)[-1]
    keys = set()
    for line in added:
        m = None
        if base in ("package.json", "package-lock.json", "npm-shrinkwrap.json"):
            m = re.match(r'^\s*"(@?[\w./-]+)"\s*:\s*"[~^<>=v*]*\d[^"]*"\s*,?\s*$', line)
            if m and m.group(1) not in NPM_SKIP:
                name = m.group(1)
                if base == "package.json" and name in ("node", "npm"):
                    keys.add("tool.%s.version" % name)
                else:
                    keys.add("project.npm." + name)
        elif fnmatch.fnmatchcase(base, "requirements*.txt") or fnmatch.fnmatchcase(base, "constraints*.txt"):
            m = re.match(r"^([A-Za-z0-9_.-]+)\s*(==|>=|<=|~=|>|<|!=)", line)
            if m:
                keys.add("project.python." + m.group(1).lower())
        elif base == "pyproject.toml":
            m = re.match(r"""^\s*"?([A-Za-z0-9_.-]+)"?\s*=\s*"[~^<>=!]*\d""", line) or re.match(
                r'^\s*"([A-Za-z0-9_.-]+)\s*(?:==|>=|<=|~=|>|<)', line
            )
            if m and m.group(1) not in ("version", "requires-python"):
                name = m.group(1).lower()
                keys.add("tool.python.version" if name == "python" else "project.python." + name)
        elif base == "go.mod":
            if re.match(r"^go\s+\d", line):
                keys.add("tool.go.version")
            m = re.match(r"^(?:require\s+)?\s*([\w.-]+\.[\w.-]+/[\w./~-]+)\s+v\d", line)
            if m:
                keys.add("project.go." + m.group(1))
        elif base == "Cargo.toml":
            m = re.match(r'^([A-Za-z0-9_-]+)\s*=\s*(?:\{[^}]*version\s*=\s*"|"[~^=<>]*\d)', line)
            if m and m.group(1) not in ("version", "edition"):
                keys.add("project.cargo." + m.group(1))
            if re.match(r"^rust-version\s*=", line):
                keys.add("tool.rust.version")
        elif base == "composer.json":
            m = re.match(r'^\s*"([a-z0-9_.-]+)/([a-z0-9_.-]+)"\s*:\s*"[~^<>=v*|\d]', line)
            if m:
                keys.add("project.composer." + m.group(2))
            elif re.match(r'^\s*"php"\s*:\s*"', line):
                keys.add("tool.php.version")
        elif base in (".nvmrc", ".node-version"):
            if re.match(r"^\s*(v?\d|lts)", line, re.I):
                keys.add("tool.node.version")
        elif base in (".python-version", ".java-version", ".go-version", ".ruby-version"):
            if re.match(r"^\s*\S", line):
                keys.add("tool.%s.version" % base[1:].split("-")[0])
        elif base == ".tool-versions":
            m = re.match(r"^(\w[\w-]*)\s+\d", line)
            if m:
                keys.add("tool.%s.version" % TOOL_NAMES.get(m.group(1), m.group(1)))
        elif fnmatch.fnmatchcase(base, "Dockerfile*") or base.endswith(".dockerfile"):
            if re.match(r"^FROM\s+(?:--platform=\S+\s+)?\S+", line):
                keys.add("runtime.container.image")
            m = re.match(r"^ARG\s+(NODE|PYTHON|GO|RUST|JAVA|PHP|GRADLE)_VERSION\b", line)
            if m:
                keys.add("tool.%s.version" % m.group(1).lower())
        elif path.startswith(".github/workflows/") or path.startswith(".github/actions/"):
            m = WORKFLOW_RE.match(line)
            if m:
                keys.add("tool.%s.version" % m.group(1))
            elif re.match(r"^\s*runs-on\s*:", line):
                keys.add("ci.runner.label")
        elif base == "libs.versions.toml":
            m = re.match(r'^\s*(kotlin|agp)\s*=\s*"', line)
            if m:
                keys.add("project.gradle.%s.version" % m.group(1))
        elif base == "gradle-wrapper.properties":
            if re.search(r"distributionUrl=.*gradle-\d", line):
                keys.add("tool.gradle.version")
    return keys


class Gql:
    def __init__(self, runs):
        self.dir = os.path.join(runs, "gql")
        os.makedirs(self.dir, exist_ok=True)
        self.log = os.path.join(self.dir, "gql.log")
        now = time.time()
        rows = []
        try:
            for line in open(self.log):
                t, c = line.split()
                if now - float(t) < 3600:
                    rows.append((float(t), int(c)))
        except FileNotFoundError:
            pass
        self.rows = rows
        with open(self.log, "w") as f:
            f.write("".join("%.0f %d\n" % r for r in rows))

    def call(self, query, **variables):
        for attempt in range(8):
            now = time.time()
            self.rows = [r for r in self.rows if now - r[0] < 3600]
            if sum(c for _, c in self.rows) + 10 >= GQL_BUDGET:
                wait = self.rows[0][0] + 3601 - now
                say("graphql budget %d points/h reached, sleeping %.0f s" % (GQL_BUDGET, wait))
                time.sleep(max(wait, 1))
            args = ["gh", "api", "graphql", "-f", "query=" + query]
            for k, v in variables.items():
                if v is not None:
                    args += ["-F" if isinstance(v, int) else "-f", "%s=%s" % (k, v)]
            out = subprocess.run(args, capture_output=True, text=True)
            data = None
            try:
                data = json.loads(out.stdout)
            except ValueError:
                pass
            if data and data.get("data") and data["data"].get("rateLimit"):
                rl = data["data"]["rateLimit"]
                self.rows.append((time.time(), int(rl["cost"])))
                with open(self.log, "a") as f:
                    f.write("%.0f %d\n" % self.rows[-1])
                if rl["remaining"] < 300:
                    wait = ts(rl["resetAt"]) - time.time() + 5
                    say("graphql remaining %d, sleeping %.0f s" % (rl["remaining"], wait))
                    time.sleep(max(wait, 1))
                return data
            err = (out.stderr + out.stdout)[:120].replace("\n", " ")
            wait = 30 * (attempt + 1)
            say("graphql error (%s), retry in %d s" % (err, wait))
            time.sleep(wait)
        raise RuntimeError("graphql failed 8 times")


def tier_a_list(gql, m, row, tdir, st_main):
    repo = row["repo"]
    owner, name = repo.split("/", 1)
    sp = os.path.join(tdir, slug(repo) + ".state.json")
    hp = os.path.join(tdir, slug(repo) + ".hits.jsonl")
    st = read_json(sp) or {"cursor": None, "closed": 0, "with_pr": 0, "done": False}
    if st["done"]:
        return st
    window = st_main["window"]
    cutoff = m["cutoff"]
    salt = m["salt"]
    pages = 0
    while not st["done"]:
        data = gql.call(LIST_QUERY, owner=owner, name=name, cursor=st["cursor"])
        issues = data["data"]["repository"]["issues"]
        out = []
        stop = False
        for n in issues["nodes"]:
            if n["createdAt"][:10] > cutoff:
                continue
            if window and n["createdAt"] < window:
                stop = True
                break
            st["closed"] += 1
            prs = [p for p in n["closedByPullRequestsReferences"]["nodes"] if p["merged"]]
            if not prs:
                continue
            st["with_pr"] += 1
            out.append(
                {
                    "number": n["number"],
                    "dev": is_dev(repo, n["number"]),
                    "created_at": n["createdAt"],
                    "closed_at": n["closedAt"],
                    "title": n["title"],
                    "body": n["body"],
                    "author": user_hash(salt, n["author"]["login"]) if n.get("author") else None,
                    "pr": {"number": prs[0]["number"], "title": prs[0]["title"]},
                }
            )
        with open(hp, "a") as f:
            for o in out:
                f.write(json.dumps(o, sort_keys=True) + "\n")
        info = issues["pageInfo"]
        st["cursor"] = info["endCursor"]
        st["done"] = stop or not info["hasNextPage"]
        write_json(sp, st)
        pages += 1
        if pages % 20 == 0:
            say("%s: %d closed, %d with pr, %d pages" % (repo, st["closed"], st["with_pr"], pages))
    say("%s: list done, %d closed in window, %d with a merged closing pr" % (repo, st["closed"], st["with_pr"]))
    return st


def read_hits(ldir, repo):
    hp = os.path.join(ldir, slug(repo) + ".hits.jsonl")
    rows = {}
    if os.path.exists(hp):
        for line in open(hp):
            h = json.loads(line)
            rows[h["number"]] = h
    return list(rows.values())


def tier_a_files(gql, row, ldir, sdir, q):
    repo = row["repo"]
    owner, name = repo.split("/", 1)
    fp = os.path.join(sdir, slug(repo) + ".sample.json")
    sample = read_json(fp)
    if sample is not None:
        return sample
    hits = read_hits(ldir, repo)
    held = sorted((h for h in hits if h["dev"] == (HALF == "dev")), key=lambda h: rank(repo, h["number"]))[:q]
    for i in range(0, len(held), FILES_PER_CALL):
        chunk = held[i : i + FILES_PER_CALL]
        body = " ".join(
            "p%d:pullRequest(number:%d){number files(first:%d){totalCount nodes{path}}}"
            % (j, h["pr"]["number"], MAX_PR_FILES)
            for j, h in enumerate(chunk)
        )
        query = (
            "query($owner:String!,$name:String!){rateLimit{cost remaining resetAt}"
            "repository(owner:$owner,name:$name){%s}}" % body
        )
        data = gql.call(query, owner=owner, name=name)["data"]["repository"]
        for j, h in enumerate(chunk):
            pr = data.get("p%d" % j)
            if pr is None:
                h["files"], h["total_files"] = None, None
                continue
            h["files"] = [f["path"] for f in pr["files"]["nodes"]]
            h["total_files"] = pr["files"]["totalCount"]
    for h in held:
        h["rank"] = rank(repo, h["number"])
        h["class"] = (
            classify(h["files"], h["total_files"]) if h.get("files") is not None else "unknown"
        )
    write_json(fp, held)
    return held


def tier_a_attrs(gh, row, sdir):
    repo = row["repo"]
    fp = os.path.join(sdir, slug(repo) + ".sample.json")
    sample = read_json(fp)
    changed = False
    for h in sample:
        if h["class"] != "env-or-config-change" or "keys" in h:
            continue
        if len(h["files"]) > MAX_ATTR_FILES:
            h["keys"] = None
            changed = True
            continue
        keys = set()
        files = gh.api("repos/%s/pulls/%d/files?per_page=100" % (repo, h["pr"]["number"]))
        for f in files:
            added = [l[1:] for l in (f.get("patch") or "").split("\n") if l.startswith("+") and not l.startswith("+++")]
            keys |= derive_keys(f["filename"], added)
        h["keys"] = sorted(keys)
        changed = True
    if changed:
        write_json(fp, sample)
    return sample


def cmd_tier_a(args):
    m = manifest()
    ldir = os.path.join(BASE, "list")
    sdir = os.path.join(hdir(), "tier-a")
    os.makedirs(sdir, exist_ok=True)
    gql = Gql(BASE)
    gh = Gh(BASE)
    lines = ["repo closed with_pr in_half_with_pr sampled env-or-config code-change docs-only attr1"]
    for row in github_repos(m):
        repo = row["repo"]
        if args.repo and repo != args.repo:
            continue
        st_main = repo_meta(gh, m, repo)
        os.makedirs(ldir, exist_ok=True)
        st = tier_a_list(gql, m, row, ldir, st_main)
        sample = tier_a_files(gql, row, ldir, sdir, args.q)
        sample = tier_a_attrs(gh, row, sdir)
        hits = read_hits(ldir, repo)
        classes = [h["class"] for h in sample]
        docs = sum(1 for h in sample if h["class"] == "code-change" and h.get("files") and all(is_docs_path(p) for p in h["files"]))
        attr1 = sum(1 for h in sample if h.get("keys") and len(h["keys"]) == 1)
        lines.append(
            "%s %d %d %d %d %d %d %d %d"
            % (repo, st["closed"], st["with_pr"], sum(1 for h in hits if h["dev"] == (HALF == "dev")), len(sample),
               classes.count("env-or-config-change"), classes.count("code-change"), docs, attr1)
        )
        say(lines[-1])
    text = "\n".join(lines) + "\n"
    if not args.repo:
        open(os.path.join(sdir, "summary.txt"), "w").write(text)
    print(text, end="")


#endregion

#region drift

def issue_text(title, body):
    return "%s\n\n%s" % (title, body)


def cmd_drift_in(args):
    """Writes the issue text Drift is run on, title and body as filed, one file per item."""
    root = os.path.join(hdir(), "drift-in")
    kept = read_json(os.path.join(hdir(), "kept.json"))
    os.makedirs(os.path.join(root, "b"), exist_ok=True)
    for k in kept:
        t = read_json(os.path.join(hdir(), "threads", slug(k["repo"]), "%d.json" % k["number"]))
        name = "%s__%d.md" % (slug(k["repo"]), k["number"])
        open(os.path.join(root, "b", name), "w", encoding="utf-8").write(issue_text(t["title"], t["body"]))
    n = len(kept)
    if args.tier_a:
        os.makedirs(os.path.join(root, "a"), exist_ok=True)
        hits = {}
        for row in github_repos(manifest()):
            for h in read_hits(os.path.join(BASE, "list"), row["repo"]):
                hits[(row["repo"], h["number"])] = h
            sample = read_json(os.path.join(hdir(), "tier-a", slug(row["repo"]) + ".sample.json")) or []
            for h in sample:
                full = hits[(row["repo"], h["number"])]
                name = "%s__%d.md" % (slug(row["repo"]), h["number"])
                open(os.path.join(root, "a", name), "w", encoding="utf-8").write(issue_text(full["title"], full["body"]))
                n += 1
    print("%d files under %s" % (n, root))


#endregion

#region check


def cmd_check(args):
    m = manifest()
    gh_repos = [r["repo"] for r in github_repos(m)]
    assert len(m["repos"]) == 43 and len(gh_repos) == 42, (len(m["repos"]), len(gh_repos))
    assert sorted(strata()) == sorted(gh_repos), "strata.yml and corpus.yml differ"
    assert per_repo_k(m) == 35
    assert m["cutoff"] == "2026-10-13" and m["cap"] == "25000"
    held = sum(1 for n in range(1, 2001) if not is_dev("acme/x", n))
    assert 900 < held < 1100, held
    a = ordered_half("acme/x", 1, 500)
    assert a == ordered_half("acme/x", 1, 500) and a[0] != 1
    assert [rank("acme/x", n) for n in a] == sorted(rank("acme/x", n) for n in a)
    assert phrase_hits("It was caused by the new Node") == ["caused by"]
    assert phrase_hits("Works on version 3 only") == ["works on version"]
    assert phrase_hits("we saw the same thing") == []
    assert phrase_hits(strip_for_filter("> due to X\n```\nregression\n```\nplain")) == []
    assert phrase_hits("Pinned to 1.2 and a Regression") == ["pinned to", "regression"]
    t = {
        "author": {"login": "rep"},
        "comments": [
            {"id": 1, "login": "rep", "assoc": "OWNER", "type": "User", "body": "due to me"},
            {"id": 2, "login": "bot", "assoc": "MEMBER", "type": "Bot", "body": "due to bot"},
            {"id": 3, "login": "dev", "assoc": "MEMBER", "type": "User", "body": "no reason"},
            {"id": 4, "login": "dev", "assoc": "MEMBER", "type": "User", "body": "this is a regression"},
        ],
        "closing": {"title": "Fix crash"},
    }
    assert [h["source"] for h in thread_hits(t)] == [4]
    t["closing"] = {"title": "Workaround for libc"}
    assert [h["source"] for h in thread_hits(t)] == [4, "closing"]
    r = redact("hi @alice and mail bob@example.com @types/node token: abcdefghijklmnop1234", "s")
    assert "alice" not in r and "bob@" not in r and "@types/node" in r and "abcdefghijkl" not in r, r
    assert user_hash("s", "Alice") == user_hash("s", "@alice") and len(user_hash("s", "a")) == 13
    long_body = " ".join("w%d" % i for i in range(3000))
    c = cut(long_body, 100)
    assert words(c) == 100 + 5 and "[... 2900 words cut ...]" in c
    thread = {
        "author": {"login": "rep"},
        "body": long_body,
        "comments": [
            {"id": 10 + i, "login": "rep" if i % 2 else "dev", "assoc": "MEMBER", "type": "User",
             "created_at": "2020-01-01T00:00:00Z", "body": long_body}
            for i in range(10)
        ] + [{"id": 99, "login": "x", "assoc": "NONE", "type": "User", "created_at": "2020-01-01T00:00:00Z", "body": "hi"}],
    }
    body, comments, note = trim(thread, "s")
    total = words(body) + sum(words(c["text"]) for c in comments)
    assert total <= THREAD_WORDS and "other-user comments dropped" in note, (total, note)
    lo, hi = wilson(5, 10)
    assert 0.23 < lo < 0.24 and 0.76 < hi < 0.77, (lo, hi)
    assert classify([".github/workflows/ci.yml", "package.json"], 2) == "env-or-config-change"
    assert classify(["package.json", "src/a.js"], 2) == "code-change"
    assert classify(["package.json"], 31) == "code-change" and classify([], 0) == "code-change"
    assert classify(["docs/guide.md"], 1) == "code-change" and is_docs_path("docs/x.txt")
    assert derive_keys("package.json", ['  "left-pad": "^1.3.0",']) == {"project.npm.left-pad"}
    assert derive_keys("package.json", ['  "node": ">=18"']) == {"tool.node.version"}
    assert derive_keys(".nvmrc", ["20.11.1"]) == {"tool.node.version"}
    assert derive_keys(".tool-versions", ["nodejs 20.1.0"]) == {"tool.node.version"}
    assert derive_keys("Dockerfile", ["FROM node:20-alpine"]) == {"runtime.container.image"}
    assert derive_keys(".github/workflows/x.yml", ["        node-version: 20"]) == {"tool.node.version"}
    assert derive_keys("go.mod", ["go 1.22"]) == {"tool.go.version"}
    assert derive_keys("requirements.txt", ["Django==4.2.1"]) == {"project.python.django"}
    assert derive_keys("package.json", ['  "version": "1.0.0",']) == set()
    rows = parse_labels('- id: "a/b#1"\n  verdict: env-cause\n  attribute: tool.node.version\n  before: "18"\n  comment: 7\n  quote: "due to node 20"\n- id: "a/b#2"\n  verdict: unclear\n  quote: null\n')
    th = {"title": "t", "body": "b", "comments": [{"id": 7, "body": "It broke due to  node 20"}], "closing": {}}
    assert len(rows) == 2 and rows[0]["comment"] == 7 and rows[1]["quote"] is None
    assert check_label(rows[0], th, "s") == [] and check_label(rows[1], th, "s") == []
    rows[0]["quote"] = "due to node 22"
    assert check_label(rows[0], th, "s") == ["quote not found verbatim in 7"]
    assert check_label({"verdict": "code-defect", "attribute": "x", "quote": "b", "comment": "issue"}, th, "s") == ["attribute must be null unless env-cause"]
    print("ok: %d github repos, K=%d, filter, redaction, trimming, ordering" % (len(gh_repos), per_repo_k(m)))


#endregion


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("check")
    s.set_defaults(fn=cmd_check)
    s = sub.add_parser("sample")
    s.add_argument("--repo", required=True)
    s.add_argument("--first", type=int, default=10)
    s.add_argument("--top", type=int)
    s.set_defaults(fn=cmd_sample)
    s = sub.add_parser("fetch")
    s.add_argument("--repo")
    s.add_argument("--k", type=int)
    s.set_defaults(fn=cmd_fetch)
    s = sub.add_parser("tier-a")
    s.add_argument("--repo")
    s.add_argument("--q", type=int, default=TIER_A_Q)
    s.set_defaults(fn=cmd_tier_a)
    s = sub.add_parser("labels")
    s.add_argument("batch", type=int, nargs="+")
    s.add_argument("--partial", action="store_true", help="accept a file that holds only some of the batch")
    s.set_defaults(fn=cmd_labels)
    s = sub.add_parser("drift-in")
    s.add_argument("--tier-a", action="store_true")
    s.set_defaults(fn=cmd_drift_in)
    s = sub.add_parser("filter")
    s.set_defaults(fn=cmd_filter)
    s = sub.add_parser("batch")
    s.set_defaults(fn=cmd_batch)
    for sp in sub.choices.values():
        sp.add_argument("--half", choices=("dev", "heldout"), default="heldout")
    args = p.parse_args()
    global HALF
    HALF = args.half
    args.fn(args)


if __name__ == "__main__":
    main()
