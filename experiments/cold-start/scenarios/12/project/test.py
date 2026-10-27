import subprocess

out = subprocess.run(["git", "--version"], capture_output=True, text=True, check=True).stdout
assert out.startswith("git version"), out
print("PASS", out.strip())
