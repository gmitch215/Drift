import subprocess

out = subprocess.run(["git", "--version"], capture_output=True, text=True)
assert out.stdout.startswith("git version"), out.stderr
print("PASS")
