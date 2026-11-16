#!/usr/bin/env python3
"""Inclusive/self wall time per method from an ART sampling .trace (dmtrace v3, dual clock)."""
import struct, sys, collections

path = sys.argv[1]
thread_name = sys.argv[2] if len(sys.argv) > 2 else "main"
top = int(sys.argv[3]) if len(sys.argv) > 3 else 60
grep = sys.argv[4] if len(sys.argv) > 4 else None

data = open(path, "rb").read()
end = data.index(b"*end\n") + 5
text = data[:end].decode("utf-8", "replace").splitlines()
section = None
methods, threads = {}, {}
for line in text:
    if line.startswith("*"):
        section = line[1:]
        continue
    if section == "threads":
        tid, name = line.split("\t", 1)
        threads[int(tid)] = name
    elif section == "methods":
        parts = line.split("\t")
        methods[int(parts[0], 16)] = parts[1] + "." + parts[2]
magic, ver, off, start = struct.unpack_from("<4sHHQ", data, end)
rec = struct.unpack_from("<H", data, end + 16)[0] if ver >= 3 else 14
pos = end + off
want = [t for t, n in threads.items() if n == thread_name]
tid_want = want[0]
incl, selft, calls = collections.Counter(), collections.Counter(), collections.Counter()
stack = []  # (method, start_wall, child_time)
total_lo = total_hi = None
while pos + rec <= len(data):
    tid, mv, tclk, wclk = struct.unpack_from("<HIII", data, pos)
    pos += rec
    if tid != tid_want:
        continue
    total_lo = wclk if total_lo is None else total_lo
    total_hi = wclk
    m, action = mv & ~3, mv & 3
    if action == 0:
        stack.append([m, wclk, 0])
        calls[m] += 1
    elif stack:
        # pop to the matching frame
        while stack:
            fm, st, child = stack.pop()
            dur = wclk - st
            if all(s[0] != fm for s in stack):
                incl[fm] += dur
            selft[fm] += dur - child
            if stack:
                stack[-1][2] += dur
            if fm == m:
                break
span = (total_hi - total_lo) / 1000.0
print(f"thread {thread_name}: span {span:.0f} ms")
name = lambda m: methods.get(m, hex(m))
def show(title, counter):
    print(f"\n== {title} (ms) ==")
    n = 0
    for m, us in counter.most_common():
        nm = name(m)
        if grep and grep not in nm:
            continue
        print(f"{us/1000:8.1f}  {calls[m]:6d}  {nm}")
        n += 1
        if n >= top:
            break
show("inclusive", incl)
show("self", selft)

# callers: python3 dmtrace.py trace main 0 - <substring> <depth>
if len(sys.argv) > 5:
    target, depth = sys.argv[5], int(sys.argv[6]) if len(sys.argv) > 6 else 6
    paths = collections.Counter()
    pos = end + off
    st = []
    while pos + rec <= len(data):
        tid, mv, tclk, wclk = struct.unpack_from("<HIII", data, pos)
        pos += rec
        if tid != tid_want:
            continue
        m, action = mv & ~3, mv & 3
        if action == 0:
            st.append(m)
            if target in name(m):
                chain = " <- ".join(name(x).rsplit(".", 2)[-2] + "." + name(x).rsplit(".", 1)[-1] for x in reversed(st[-depth-1:-1]))
                paths[chain] += 1
        elif st:
            while st and st.pop() != m:
                pass
    print(f"\n== callers of {target} ==")
    for c, n in paths.most_common(12):
        print(f"{n:5d}  {c}")

# callees: DMT_CALLEES=<exact method name suffix> python3 dmtrace.py trace main
import os
tgt = os.environ.get("DMT_CALLEES")
if tgt:
    kids = collections.Counter(); kcalls = collections.Counter()
    pos = end + off
    st = []  # [m, start]
    while pos + rec <= len(data):
        tid, mv, tclk, wclk = struct.unpack_from("<HIII", data, pos)
        pos += rec
        if tid != tid_want:
            continue
        m, action = mv & ~3, mv & 3
        if action == 0:
            st.append([m, wclk])
        else:
            while st:
                fm, s0 = st.pop()
                if st and name(st[-1][0]).endswith(tgt) and all(not name(x[0]).endswith(tgt) for x in st[:-1]):
                    kids[fm] += wclk - s0; kcalls[fm] += 1
                if fm == m:
                    break
    print(f"\n== direct callees of {tgt} ==")
    for m, us in kids.most_common(14):
        print(f"{us/1000:8.1f}  {kcalls[m]:5d}  {name(m)}")
