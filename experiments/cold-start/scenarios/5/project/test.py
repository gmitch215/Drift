import os

files = [open(os.devnull) for _ in range(300)]
assert len(files) == 300
print("PASS")
