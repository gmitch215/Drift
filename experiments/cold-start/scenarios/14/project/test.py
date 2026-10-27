import os

umask = int(os.environ.get("SLSKD_UMASK", "000"), 8)
os.umask(umask)
os.makedirs("downloads/album", exist_ok=True)
mode = os.stat("downloads/album").st_mode & 0o777
assert mode & 0o020, f"importer cannot write: album mode is {mode:o}"
print("PASS")
