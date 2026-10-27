import os
import tempfile

d = tempfile.mkdtemp()
handles = []
for i in range(300):
    handles.append(open(os.path.join(d, f"shard-{i}.idx"), "w"))
for h in handles:
    h.close()
print("PASS")
