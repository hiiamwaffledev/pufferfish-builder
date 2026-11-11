#!/usr/bin/env python3
"""prune.py <bake.log> <pufferfish-hooks.jar> <exclude-file>: append the hooks Mixin rejected during a bake (by stub
name pf$hook<id>$...) to the exclude file as mixinClass#handler+desc. Prints how many were new."""
import re, sys, zipfile
log, jar, excl = sys.argv[1:4]
table = {}
with zipfile.ZipFile(jar) as z:
    for line in z.read("pufferfish-hooks.txt").decode().splitlines():
        p = line.split("\t")
        if len(p) >= 5: table[p[0]] = f"{p[2]}#{p[3]}{p[4]}"
bad = set()
for line in open(log, errors="replace"):
    if re.search(r"Exception|Error|error", line):
        for m in re.finditer(r"pf\$hook(\d+)\$", line): bad.add(m.group(1))
try: old = set(open(excl).read().split("\n"))
except FileNotFoundError: old = set()
new = {table[i] for i in bad if i in table} - old
with open(excl, "a") as f:
    for k in sorted(new): f.write(k + "\n")
print(f"[prune] {len(bad)} failing hook stubs in log, {len(new)} newly excluded, {len(old | new) - (1 if '' in old else 0)} total")
