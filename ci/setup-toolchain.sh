#!/usr/bin/env bash
# setup-toolchain.sh: recreate the Linux -> ios-aarch64 GraalVM cross toolchain from OFFICIAL GraalVM CE downloads.
# Nothing prebuilt is redistributed by this repo; everything below is derived from the two upstream tarballs.
#   graalvm-cross/   official GraalVM CE (linux-x64) + Darwin static libs copied into lib/**/ios-aarch64/glibc
#   ref/graalvm-darwin/Contents/Home   official GraalVM CE (macos-aarch64), source of the Darwin bits
#   aot/cross/{darwin,linux}-java.base, darwin-only/<module>   extracted JDK classes (GenJdkSubst input, --patch-module)
#   aot/cross/{objectfile,builder}-patch   two ASM-patched builder classes (see tools/cross-src/Patch*.java)
#   aot/cross/patch-args   builder flags wiring the above in
# Requires mc/libraries (ASM) → run ci/fetch-mc.sh first.
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh

GV_TAG=graal-25.4.4.1.1
GV_VER=25.0.4.1.1
BASE=https://github.com/graalvm/graalvm-ce-builds/releases/download/$GV_TAG
LIN=graalvm-community-jdk-25i4-${GV_VER}_linux-x64_bin.tar.gz
MAC=graalvm-community-jdk-25i4-${GV_VER}_macos-aarch64_bin.tar.gz
DL=${PF_DL_DIR:-$PWD/.dl}; mkdir -p "$DL"

fetch() { # file → downloads + verifies sha256 (cached across runs via actions/cache on .dl)
  local f=$1
  if [[ -f $DL/$f && -f $DL/$f.sha256 ]] && (cd "$DL" && echo "$(cat $f.sha256)  $f" | sha256sum -c --quiet); then
    log "cached: $f"; return; fi
  log "downloading $f"
  curl -fsSL --retry 4 -o "$DL/$f.sha256" "$BASE/$f.sha256"
  curl -fsSL --retry 4 -o "$DL/$f" "$BASE/$f"
  (cd "$DL" && echo "$(cat $f.sha256)  $f" | sha256sum -c) || die "sha256 mismatch for $f"
  log "verified $f ($(du -h "$DL/$f" | cut -f1))"
}

step "download official GraalVM CE $GV_VER (linux-x64 builder, macos-aarch64 for Darwin bits)"
fetch "$LIN"; fetch "$MAC"

step "unpack"
rm -rf graalvm-cross ref/graalvm-darwin graalvm
mkdir -p graalvm-cross ref/graalvm-darwin
tar xzf "$DL/$LIN" -C graalvm-cross --strip-components=1
tar xzf "$DL/$MAC" -C ref/graalvm-darwin --strip-components=1
ln -s graalvm-cross graalvm
DH=ref/graalvm-darwin/Contents/Home
[[ -f $DH/release ]] || die "unexpected macOS tarball layout (no $DH/release)"
log "$(grep -h '^JAVA_RUNTIME_VERSION' graalvm-cross/release) / darwin: $(grep -h '^JAVA_RUNTIME_VERSION' $DH/release)"

step "Darwin static libs -> ios-aarch64 target dirs"
for d in lib/svm/clibraries lib/static; do
  mkdir -p graalvm-cross/$d/ios-aarch64/glibc
  cp $DH/$d/darwin-aarch64/*.a graalvm-cross/$d/ios-aarch64/glibc/
  log "$d: $(ls graalvm-cross/$d/ios-aarch64/glibc | wc -l) archives"
done

step "extract JDK classes (jimage)"
X=$(mktemp -d)
graalvm/bin/jimage extract --dir "$X/darwin" $DH/lib/modules
graalvm/bin/jimage extract --dir "$X/linux" graalvm-cross/lib/modules
rm -rf aot/cross/darwin-java.base aot/cross/linux-java.base aot/cross/darwin-only
mv "$X/darwin/java.base" aot/cross/darwin-java.base
cp -r "$X/linux/java.base" aot/cross/linux-java.base
mkdir -p "$X/darwin/java.base" && cp -r aot/cross/darwin-java.base/. "$X/darwin/java.base/"
MODS="java.base java.desktop java.prefs jdk.charsets jdk.net jdk.sctp"
for m in $MODS; do  # classes only the macOS JDK has
  out=aot/cross/darwin-only/$m; mkdir -p "$out"
  comm -23 <(cd "$X/darwin/$m" && find . -name '*.class' | sort) <(cd "$X/linux/$m" && find . -name '*.class' | sort) |
    while read -r c; do mkdir -p "$out/$(dirname "$c")"; cp "$X/darwin/$m/$c" "$out/$c"; done
  log "darwin-only/$m: $(find "$out" -name '*.class' | wc -l) classes"
done
rm -rf "$X"
log "java.base classes: darwin $(find aot/cross/darwin-java.base -name '*.class' | wc -l), linux $(find aot/cross/linux-java.base -name '*.class' | wc -l)"

step "builder patches (ASM)"
ASMD=mc/libraries/org/ow2/asm
ASM=$(ls $ASMD/asm/*/asm-*.jar | head -1):$(ls $ASMD/asm-commons/*/asm-commons-*.jar | head -1)
rm -rf aot/cross/patch-build aot/cross/objectfile-patch aot/cross/builder-patch
graalvm/bin/javac -nowarn -cp "$ASM" -d aot/cross/patch-build tools/cross-src/PatchHostOS.java tools/cross-src/PatchFsAccessor.java
graalvm/bin/java -cp "$ASM:aot/cross/patch-build" PatchHostOS graalvm-cross/lib/svm/builder/objectfile.jar aot/cross/objectfile-patch MAC_OS_X
graalvm/bin/java -cp "$ASM:aot/cross/patch-build" PatchFsAccessor graalvm-cross/lib/svm/builder/svm.jar aot/cross/builder-patch
log "patched: $(cd aot/cross && find objectfile-patch builder-patch -name '*.class' | tr '\n' ' ')"

step "patch-args"
{
  for m in $MODS; do echo "-J--patch-module=$m=$PWD/aot/cross/darwin-only/$m"; done
  echo "-J--patch-module=org.graalvm.nativeimage.objectfile=$PWD/aot/cross/objectfile-patch"
  echo "-J--patch-module=org.graalvm.nativeimage.builder=$PWD/aot/cross/builder-patch"
  echo "-J--add-exports=java.base/sun.nio.fs=org.graalvm.nativeimage.builder"
} > aot/cross/patch-args
sed 's/^/    /' aot/cross/patch-args
log "toolchain ready"
