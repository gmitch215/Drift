from datetime import datetime, timezone

since = datetime.fromisoformat("2024-05-01T12:00:00").timestamp()
kept = []
with open("app.log") as f:
    for line in f:
        stamp = datetime.fromisoformat(line[:19]).replace(tzinfo=timezone.utc).timestamp()
        if stamp >= since:
            kept.append(line)
assert len(kept) == 4, f"expected 4 lines, got {len(kept)}"
print("PASS")
