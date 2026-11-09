#!/usr/bin/env python3
"""fetch-top.py <mc-version> <count> <out-dir>: download the most-downloaded Fabric mods for <mc-version> from Modrinth
(primary file of the newest matching version). Input for ModApiScan; the jars are only scanned, never redistributed."""
import json, sys, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

API = "https://api.modrinth.com/v2"
UA = {"User-Agent": "pufferfish-builder/0.1 (github.com/hiiamwaffledev/pufferfish-builder)"}
mc, count, out = sys.argv[1], int(sys.argv[2]), Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()

hits = []
facets = json.dumps([["categories:fabric"], [f"versions:{mc}"], ["project_type:mod"]])
while len(hits) < count:
    q = urllib.parse.urlencode({"facets": facets, "index": "downloads", "limit": min(100, count - len(hits)), "offset": len(hits)})
    page = json.loads(get(f"{API}/search?{q}"))["hits"]
    if not page: break
    hits += page

def one(h):
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([mc])})
    try:
        vs = json.loads(get(f"{API}/project/{h['project_id']}/version?{q}"))
        if not vs: return f"{h['slug']}: no {mc} fabric version"
        f = next((f for f in vs[0]["files"] if f["primary"]), vs[0]["files"][0])
        (out / f"{h['slug']}.jar").write_bytes(get(f["url"]))
        return f"{h['slug']} {vs[0]['version_number']}"
    except Exception as e:
        return f"{h['slug']}: FAILED {e}"

with ThreadPoolExecutor(4) as ex:
    for line in ex.map(one, hits): print(line)
print(f"[fetch-top] {len(list(out.glob('*.jar')))} jars for {mc}")
