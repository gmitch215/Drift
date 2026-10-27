import time

stamp = 1772325000  # 2026-03-01 00:30 UTC
day = time.strftime("%Y-%m-%d", time.localtime(stamp))
assert day == "2026-03-01", day
print("PASS")
