#!/usr/bin/env python3
"""Resolve + download a Minecraft version (optionally with Fabric) into ./mc.

Usage: fetch.py <mc_version> [--fabric <loader_version>]
Writes mc/instances/<id>/launch.json describing classpath, main class, args.
Reuses Prism Launcher's library/asset caches when sha1 matches.
"""
import hashlib, json, os, platform, shutil, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "mc"
PRISM = Path.home() / ".local/share/PrismLauncher"
UA = {"User-Agent": "Pufferfish-Launcher/0.1"}
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json"


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def get_json(url):
    return json.loads(get(url))


def sha1(p):
    h = hashlib.sha1()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_file(url, dest, want_sha1=None, prism_rel=None):
    dest = Path(dest)
    if dest.exists() and (not want_sha1 or sha1(dest) == want_sha1):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    if prism_rel:
        cand = PRISM / prism_rel
        if cand.exists() and (not want_sha1 or sha1(cand) == want_sha1):
            shutil.copyfile(cand, dest)
            return dest
    data = get(url)
    if want_sha1 and hashlib.sha1(data).hexdigest() != want_sha1:
        raise RuntimeError(f"sha1 mismatch {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(dest)
    return dest


OS_NAME = {"Linux": "linux", "Darwin": "osx", "Windows": "windows"}[platform.system()]
ARCH = platform.machine().lower()


def rules_ok(rules):
    if not rules:
        return True
    allowed = False
    for r in rules:
        if "features" in r:
            continue  # demo/quickplay/etc: treat as not matching
        os_ = r.get("os", {})
        match = True
        if "name" in os_ and os_["name"] != OS_NAME:
            match = False
        if "arch" in os_ and os_["arch"] not in ARCH:
            match = False
        if match:
            allowed = r["action"] == "allow"
    return allowed


def maven_path(name):
    parts = name.split(":")
    group, art, ver = parts[0], parts[1], parts[2]
    cls = f"-{parts[3]}" if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{art}/{ver}/{art}-{ver}{cls}.jar"


def main():
    args = sys.argv[1:]
    mc = args[0]
    fabric = args[args.index("--fabric") + 1] if "--fabric" in args else None

    man = get_json(MANIFEST)
    entry = next(v for v in man["versions"] if v["id"] == mc)
    vj = get_json(entry["url"])

    libs_dir = ROOT / "libraries"
    jobs = []  # (url, dest, sha1, prism_rel)
    classpath = []
    natives = []

    for lib in vj["libraries"]:
        if not rules_ok(lib.get("rules")):
            continue
        art = lib.get("downloads", {}).get("artifact")
        if art:
            dest = libs_dir / art["path"]
            jobs.append((art["url"], dest, art["sha1"], f"libraries/{art['path']}"))
            # natives-* classifier jars go on classpath too (LWJGL loads from them)
            classpath.append(str(dest))
            if "natives" in lib["name"]:
                natives.append(str(dest))

    client = vj["downloads"]["client"]
    client_jar = ROOT / "versions" / mc / f"{mc}.jar"
    jobs.append((client["url"], client_jar, client["sha1"], f"libraries/com/mojang/minecraft/{mc}/minecraft-{mc}-client.jar"))

    main_class = vj["mainClass"]
    game_args = vj["arguments"]["game"]
    jvm_args = vj["arguments"]["jvm"]

    if fabric:
        fp = get_json(FABRIC_META.format(mc=mc, loader=fabric))
        main_class = fp["mainClass"]
        # fabric libs replace same group:artifact (e.g. asm)
        have = {}
        for lib in fp["libraries"]:
            p = maven_path(lib["name"])
            base = lib.get("url", "https://maven.fabricmc.net/").rstrip("/")
            dest = libs_dir / p
            jobs.append((f"{base}/{p}", dest, lib.get("sha1"), f"libraries/{p}"))
            ga = ":".join(lib["name"].split(":")[:2])
            have[ga] = str(dest)
        # drop vanilla entries overridden by fabric
        def ga_of(path):
            rel = Path(path).relative_to(libs_dir).parts
            return ".".join(rel[:-3]) + ":" + rel[-3]
        classpath = [c for c in classpath if ga_of(c) not in have] + list(have.values())
        game_args = game_args + fp.get("arguments", {}).get("game", [])
        jvm_args = jvm_args + fp.get("arguments", {}).get("jvm", [])

    classpath.append(str(client_jar))

    # assets
    ai = vj["assetIndex"]
    idx_path = ROOT / "assets" / "indexes" / f"{ai['id']}.json"
    fetch_file(ai["url"], idx_path, ai["sha1"], f"assets/indexes/{ai['id']}.json")
    objs = [] if os.environ.get("PF_NO_ASSETS") else json.loads(idx_path.read_text())["objects"].values()
    for obj in objs:
        h = obj["hash"]
        rel = f"objects/{h[:2]}/{h}"
        jobs.append((f"https://resources.download.minecraft.net/{h[:2]}/{h}", ROOT / "assets" / rel, h, f"assets/{rel}"))

    print(f"[fetch] {len(jobs)} files")
    done = [0]
    def run(j):
        fetch_file(*j)
        done[0] += 1
        if done[0] % 500 == 0:
            print(f"  {done[0]}/{len(jobs)}", flush=True)
    with ThreadPoolExecutor(8) as ex:
        list(ex.map(run, jobs))

    inst_id = mc + (f"-fabric-{fabric}" if fabric else "")
    inst = ROOT / "instances" / inst_id
    (inst / "game").mkdir(parents=True, exist_ok=True)
    launch = {
        "id": inst_id,
        "mc": mc,
        "mainClass": main_class,
        "classpath": classpath,
        "natives": natives,
        "assetsDir": str(ROOT / "assets"),
        "assetIndex": ai["id"],
        "gameDir": str(inst / "game"),
        "gameArgs": [a for a in game_args if isinstance(a, str)],
        "jvmArgs": [a for a in jvm_args if isinstance(a, str)],
        "javaMajor": vj.get("javaVersion", {}).get("majorVersion"),
    }
    (inst / "launch.json").write_text(json.dumps(launch, indent=2))
    print(f"[fetch] wrote {inst/'launch.json'}  main={main_class}  java={launch['javaMajor']}")


if __name__ == "__main__":
    main()
