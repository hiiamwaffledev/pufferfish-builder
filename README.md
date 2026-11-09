# pufferfish-builder

GitHub Actions builder for Pufferfish Launcher: runs the memory-heavy GraalVM `native-image` step
(Minecraft Java → `ios-aarch64` object) on the free 16 GB public-repo runner, because a mod-capable build
(Crema runtime class loading / Ristretto JIT) does not fit a 16 GB desktop.

## What's in here
- `ci/` build scripts (fetch, toolchain, build, encrypt, logging, memory monitor, job summary)
- `tools/` launcher fetcher, classpath sanitizer, Linux→iOS cross-compile helpers (builder patches, JDK substitutions)
- `aot/` reflection/resource configs and cross-compile lists, `aot-support/` and `jit/` GraalVM substitutions

**No Minecraft or Mojang code is in this repository.** Each run downloads the official jars from Mojang (and
Fabric's Maven) and official GraalVM CE releases from Oracle's GitHub. The built object contains compiled Minecraft
code, so it is uploaded **only encrypted** (AES-256, key in a repository secret) and expires after 3 days. Linking
and installing happen locally on the owner's machine.

## Run
Actions → build → Run workflow. Inputs: `mode` (vanilla | crema | crema-jit), `instance`, `analysis`, `heap`,
`parallelism`, `gc`, `extra`. Logs (native-image output, GC log, 10 s memory samples, periodic heap histograms,
stage table) are uploaded as the `logs` artifact and summarised on the run page.
