#!/usr/bin/env python3
"""launch.py <instance-id> [--java PATH] [--print] [--bake DIR | --flat DIR] [-- extra jvm args...]

Offline dev account.
  --bake DIR  run Knot once, dump the transformed closed world into DIR, exit
  --flat DIR  run Fabric from a baked closed world: no Knot, no Mixin, plain classpath
"""
import json, os, sys, uuid, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
RUNTIME_JAR = ROOT / "runtime/pufferfish-runtime.jar"
a = sys.argv[1:]
extra = a[a.index("--")+1:] if "--" in a else []
a = a[:a.index("--")] if "--" in a else a
def opt(k):
  return a[a.index(k)+1] if k in a else None
inst = ROOT / "mc/instances" / a[0]
java = opt("--java") or str(ROOT / "graalvm/bin/java")
L = json.loads((inst / "launch.json").read_text())
nat = inst / "natives"
name = os.environ.get("PUFFERFISH_USER", "PufferDev")

classpath = list(L["classpath"])
main = L["mainClass"]
game_jar = classpath[-1]
bake, flat = opt("--bake"), opt("--flat")
if bake:
  bake = str(Path(bake).resolve())
  classpath.append(str(RUNTIME_JAR))
  main = "dev.pufferfish.runtime.BakeMain"
  extra = [f"-Dpufferfish.bake.out={bake}"] + extra
elif flat:
  flat = Path(flat).resolve()
  # Mojang signs the client jar; baked classes in the same packages would fail the signer check
  unsigned = flat / "minecraft-unsigned.jar"
  if not unsigned.exists():
    import zipfile, re
    with zipfile.ZipFile(game_jar) as zi, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zo:
      for it in zi.infolist():
        if re.match(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)$", it.filename): continue
        data = zi.read(it.filename)
        if it.filename == "META-INF/MANIFEST.MF":  # drop per-entry digests
          data = data.replace(b"\r\n", b"\n").split(b"\n\n")[0] + b"\n\n"
        zo.writestr(it, data)
  classpath = [str(unsigned) if c == game_jar else c for c in classpath]
  game_jar = str(unsigned)
  classpath = [str(flat / "classes"), str(flat / "res")] + classpath + [str(RUNTIME_JAR)]
  main = "dev.pufferfish.runtime.FlatMain"
  extra = [f"-Dfabric.gameJarPath={game_jar}"] + extra

sub = {
  "natives_directory": str(nat), "launcher_name": "pufferfish", "launcher_version": "0.1",
  "classpath": os.pathsep.join(classpath), "auth_player_name": name,
  "version_name": L["mc"], "game_directory": L["gameDir"], "assets_root": L["assetsDir"],
  "assets_index_name": L["assetIndex"], "auth_uuid": str(uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:"+name)).replace("-",""),
  "auth_access_token": "0", "clientid": "0", "auth_xuid": "0", "version_type": "release",
}
def s(x):
  for k, v in sub.items(): x = x.replace("${"+k+"}", v)
  return x
jv = list(L["jvmArgs"])
if "-cp" in jv: i = jv.index("-cp"); del jv[i:i+2]
import shlex
cmd = [java, "-Xmx2G"] + [s(x) for x in jv] + extra + ["-cp", sub["classpath"], main] + [s(x) for x in L["gameArgs"]] + shlex.split(os.environ.get("PF_GAME_ARGS", ""))  # e.g. --quickPlaySingleplayer World
if "--print" in a: print(json.dumps(cmd)); sys.exit()
subprocess.run([sys.executable, str(ROOT/"tools/opts.py"), L["gameDir"]], check=True)
os.chdir(L["gameDir"]); os.execv(cmd[0], cmd)
