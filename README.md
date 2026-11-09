> [!CAUTION]
> This project is in early development. Expect things to not work quite right and there to be significant changes and breaking public API updates as development progresses. Contributions and feedback are welcome, but please be aware that the codebase is still evolving rapidly.

<h1 align="center">
  <br>
  <a href="https://github.com/rexglue/rexglue-sdk">
    <img src="https://github.com/rexglue/rexglue-media/blob/main/ReX_Banner.png" alt="ReXGlue banner">
  </a>
  <br>
  <br>
  <a href="https://discord.gg/CNTxwSNZfT">
    <img src="https://img.shields.io/badge/Discord-Join%20Server-5865F2?logo=discord&logoColor=white" alt="Discord">
  </a>
  <a href="https://github.com/rexglue/rexglue-sdk/stargazers">
    <img src="https://img.shields.io/github/stars/rexglue/rexglue-sdk" alt="rexglue-sdk stargazers">
  </a>
</h1>

ReXGlue converts Xbox 360 PowerPC code into portable C++ that runs natively on modern platforms.

ReXGlue is heavily rooted on the foundations of [Xenia](https://github.com/xenia-project), the Xbox 360 emulator. Rather than interpreting or JIT-compiling PPC instructions at runtime, ReXGlue takes a different path: it generates C++ source code ahead of time, an approach inspired by [XenonRecomp](https://github.com/hedge-dev/XenonRecomp) and [rexdex's recompiler](https://github.com/rexdex/recompiler).

Latest SDK builds and releases are published on [GitHub Releases](https://github.com/rexglue/rexglue-sdk/releases). Join the [Discord server](https://discord.gg/CNTxwSNZfT) for updates and share what you have created.

## Builds

| Channel | CI | Download |
| --- | --- | --- |
| Release | [![win-amd64](https://github.com/rexglue/rexglue-sdk/actions/workflows/build-win-amd64.yaml/badge.svg)](https://github.com/rexglue/rexglue-sdk/actions/workflows/build-win-amd64.yaml) [![linux-amd64](https://github.com/rexglue/rexglue-sdk/actions/workflows/build-linux-amd64.yaml/badge.svg)](https://github.com/rexglue/rexglue-sdk/actions/workflows/build-linux-amd64.yaml) [![linux-arm64](https://github.com/rexglue/rexglue-sdk/actions/workflows/build-linux-aarch64.yaml/badge.svg)](https://github.com/rexglue/rexglue-sdk/actions/workflows/build-linux-aarch64.yaml) [![android-arm64](https://github.com/Artjom777/rexglue-sdk-android/actions/workflows/build-android.yaml/badge.svg)](https://github.com/Artjom777/rexglue-sdk-android/actions/workflows/build-android.yaml) | [Latest stable](https://github.com/Artjom777/rexglue-sdk-android/releases/latest) |
| Nightly | [![nightly](https://github.com/rexglue/rexglue-sdk/actions/workflows/nightly.yaml/badge.svg)](https://github.com/rexglue/rexglue-sdk/actions/workflows/nightly.yaml) | [Latest pre-release](https://github.com/Artjom777/rexglue-sdk-android/releases?q=prerelease%3Atrue) |

## Android & POCO X7 Pro Support

ReXGlue has native Android ARM64 support with dedicated optimizations for the **POCO X7 Pro** (MediaTek Dimensity 8400 / ARMv9 / Mali-G720 GPU):
- **On-Device Codegen**: Run `rexglue` CLI directly on Android (via Termux, PRoot, or shell) to decompile Xbox 360 `default.xex` into C++ code without a PC.
- **MediaTek / Mali-G720 Vulkan Compatibility**:
  - Relaxed geometry shader / fillModeNonSolid / atomics requirements for Mali GPU architecture.
  - 16KB page alignment (`-Wl,-z,max-page-size=16384`) for Android 15 / HyperOS 2.0 compatibility.
  - Safe memory mapping with `MAP_FIXED_NOREPLACE` to protect Android ART, Scudo and Vulkan driver memory spaces.
  - MediaTek Dimensity big/prime cluster core affinity (`REX_THREAD_BIG_CORES=1`).
  - Android SIGBUS recovery and POSIX exception unwinding via `process_vm_readv`.
- **Packaging Tools**: Scripts under `tools/android/` for building the SDK, running codegen, compiling ports, and generating signed APKs.

### Quickstart on Android / POCO X7 Pro (No PC Needed)

1. **Run Codegen directly on the phone**:
   ```bash
   # Decompile any Xbox 360 game default.xex into C++:
   ./tools/android/run_codegen.sh /sdcard/XboxGames/MyGame/default.xex MyGame
   ```

2. **Compile the game for Android**:
   ```bash
   ./tools/android/build_game.sh ./MyGame
   ```

3. **Package as Android APK**:
   ```bash
   ./tools/android/build_apk.sh ./MyGame/build/android/libmain.so MyGame
   # Installs or produces out/apk/MyGame.apk
   ```

### Building via GitHub Actions (Cloud)
Push commits to this repository or trigger the `Build ReXGlue Android SDK & POCO X7 Pro` workflow under GitHub Actions to automatically compile and release pre-built APKs and SDK archives using GitHub's servers.

## Quickstart

For quick start guide, full CLI reference, and config file options, see the [wiki](https://github.com/rexglue/rexglue-sdk/wiki).

# **Disclaimer**
ReXGlue is not affiliated with nor endorsed by Microsoft or Xbox. It is an independent project created for educational and development purposes. All trademarks and copyrights belong to their respective owners. 

This project is not intended to promote piracy nor unauthorized use of copyrighted material. Any misuse of this software to endorse or enable this type of activity is strictly prohibited.


# Credits

## ReXGlue
- [Tom (crack)](https://github.com/tomcl7) - Project Founder
- [Loreaxe](https://github.com/Loreaxe) - Linux Contributor
- [mystixor](https://github.com/Mystixor) - Windows Contributor
- [Graine25](https://github.com/Graine25) - Project Support
- [Carlos Estrague (mrcmunir)](https://github.com/mrcmunir) - Linux / ARM64 Contributor
- [sanjay900](https://github.com/sanjay900) - Linux / SDL Contributor
- [Toby](https://github.com/TbyDtch) - Project Support
- [Roxxsen](https://github.com/Roxxsen) - CI/CD Contributor

The list above is not exhaustive. Thanks to everyone in the ReXGlue community who contributes code, files issues, tests builds, and keeps the project moving.

## Very Special Thank You:
- [Project Xenia](https://github.com/xenia-project/xenia/tree/master/src/xenia) - Their invaluable work on Xbox 360 emulation laid the groundwork for ReXGlue's development. This project (and numerous others) would not exist without their hard work and dedication.
- [XenonRecomp](https://github.com/hedge-dev/XenonRecomp) - For pioneering the modern static recompilation approach for Xbox 360. A lot of the codegen analysis logic and instruction translations are based on their work. Thank you!
- [rexdex's recompiler](https://github.com/rexdex/recompiler) - The OG static recompiler for Xbox 360. 
- Many others in the Xbox 360 homebrew and modding communities whose work and research have contributed to the collective knowledge that makes projects like this possible.
