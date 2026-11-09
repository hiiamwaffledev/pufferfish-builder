#!/usr/bin/env python3
"""opts.py <gameDir>: force launcher-managed options (Vulkan backend) before every launch."""
import sys, re
from pathlib import Path
FORCE = {"preferredGraphicsBackend": '"vulkan"'}
p = Path(sys.argv[1]) / "options.txt"
lines = p.read_text().splitlines() if p.exists() else []
keys = set()
out = []
for l in lines:
    k = l.split(":", 1)[0]
    if k in FORCE: l = f"{k}:{FORCE[k]}"; keys.add(k)
    out.append(l)
out += [f"{k}:{v}" for k, v in FORCE.items() if k not in keys]
p.write_text("\n".join(out) + "\n")
