# Persona Q2 for Android (native port)

An Android app that runs **your own copy** of Persona Q2: New Cinema Labyrinth (Nintendo 3DS, US) on an arm64 phone or
tablet. The game's code is translated to native ARM64 code **on your phone**, the first time you play: the APK holds no
game code or data, only the engine and a C compiler.

> [!IMPORTANT]
> **No game files are included, here or in the APK.** This repository and its releases hold no game code, assets,
> keys or firmware. You need your own legally dumped copy of the game (a **decrypted** `.cia` or `.3ds`). How to dump
> it from your console is **not** covered here. Please do not ask for game files.

Persona Q2 is © ATLUS / © SEGA. This is an unofficial fan project, not affiliated with or endorsed by ATLUS, SEGA or
Nintendo.

## Community and support

- **Discord** (English and Spanish channels), for news, help and testing: https://discord.gg/rMgRneBTCe
- **Support the developer (optional):** [GitHub Sponsors](https://github.com/sponsors/Brandon04Gonzalez). These ports
  are free and made for preservation. Donating is entirely voluntary and helps with the costs of making them (test
  devices, hardware, servers). Nothing is locked behind a donation.

## Why not a native (NCE) build like the Switch ports?

The Switch ports ([P5R](https://github.com/Brandon04Gonzalez/p5r-android),
[P4G](https://github.com/Brandon04Gonzalez/p4g-android), [P4AU](https://github.com/Brandon04Gonzalez/p4au-android),
[NieR](https://github.com/Brandon04Gonzalez/nier-android)) run the game's own code directly on the phone's CPU (native
code execution, NCE): the Switch and Android phones share the same 64-bit ARM architecture (AArch64).

The 3DS does not: its CPU is an ARM11, a **32-bit** ARM (ARMv6K). An Android app is a 64-bit process, and a 64-bit
process cannot run 32-bit ARM code; many recent phones cannot run 32-bit code at all, their CPUs dropped it. So the
3DS game's code cannot run as it is: it has to be **translated**. That is what this port does, once, on your phone
(the same way as [Persona Q](https://github.com/Brandon04Gonzalez/pq-android)).

## How it works

- **[3dsrecomp](3dsrecomp/)** translates the game's ARM11 code into C, ahead of time ("static recompilation").
- **[Zig](https://ziglang.org/)'s C compiler** (`zig cc`, clang inside; 0.17.0 for aarch64 Linux, shipped in the APK)
  compiles that C into a native library for your phone. This happens in the app, the first time you play, with the
  progress on screen; later starts load the library right away.
- **[Zakuro](zakuro/)** is the 3DS runtime: the operating system services, the GPU on Vulkan, audio, input and touch
  controls. It runs the translated code and loads the library.

## Install

1. Copy your decrypted Persona Q2 `.cia` (or `.3ds`, US version) to the phone.
2. Install **PersonaQ2.apk** from the [Releases](../../releases) page and open it.
3. **1. Game:** tap *Elegir archivo del juego* and pick your `.cia`. The launcher copies it into the app's own folder
   (about 2.9 GB), so you can delete your copy afterwards.
4. **2. Spanish translation (optional):** tap *Importar parche en español* and pick your copy of the Spanish fan patch
   (by Artur16211, HxCannon and Manuovi_07): either its folder, the one with `code.bin`, `exheader.bin` and
   `romfs/Patch.cpk` (`luma/titles/00040000001D7100`) or any folder above it, or its `.zip`. Android does not let apps
   pick the Download folder itself, so keep the patch in a folder of its own (for example `Download/Persona Q2 Patch`).
5. **3. HD textures (optional):** import an HD texture pack `.zip` in Zakuro's format, if you have one.
6. Press **JUGAR / PLAY**. The first time, the app unpacks its compiler and then **translates the game for your
   phone**: keep the app open and in the foreground until it ends (about 75 minutes on a Lenovo Tab M11, Snapdragon
   685; less on faster phones). After that, the game starts right away. Installing, changing or removing the patch
   translates the game again, because the patch changes its code.

## Requirements

- An arm64 Android phone or tablet with Android 8 or newer, Vulkan 1.1, 4 GB of RAM or more (6 GB recommended for the
  first translation).
- About 4 GB of free storage: the game's copy (2.9 GB) and the translation (the compiler's files, the generated C and
  the native library).

## Building the APK

Windows with Git Bash, the Android SDK (build-tools 35.0.1, platform 35) and NDK 27.2.12479018, a JDK 17+ and Rust with
the `aarch64-linux-android` target. The folders sit side by side as here (`pq2-android` reaches `../zakuro` and
`../3dsrecomp`):
```bash
# Zig 0.17.0 for aarch64 Linux, unpacked in build/zig-android, and its library trimmed for the APK
mkdir -p build/zig-android
curl -L -o zig.tar.xz https://ziglang.org/download/0.17.0/zig-aarch64-linux-0.17.0.tar.xz   # sha256 9e8d1166...
tar -xJf zig.tar.xz -C build/zig-android; python tools/make_zig_lib.py build/zig-android
source tools/ndkenv.sh                      # NDK paths at the top (ANDROID_HOME)
(cd pq2-android && cargo build --release --target aarch64-linux-android)
bash tools/build_apk.sh arm64-v8a           # out/PersonaQ2-arm64-v8a.apk; ANDROID_HOME, JDK and ZIG can be set
```
The build signs the APK with `android/pq2port.keystore`, creating a new key the first time; the releases are signed
with the developer's own key, so an APK you build cannot update one installed from the releases.

## En español

Una app de Android que ejecuta **tu propia copia** de Persona Q2: New Cinema Labyrinth (3DS, EE. UU.) en un móvil o
tablet arm64. El código del juego se traduce a código nativo **en tu móvil** la primera vez que juegas: el APK no lleva
código ni datos del juego, solo el motor y un compilador de C.

- **Discord** (canales en español e inglés): https://discord.gg/rMgRneBTCe
- **Apoyar al desarrollador (opcional):** [GitHub Sponsors](https://github.com/sponsors/Brandon04Gonzalez). Los ports
  son gratis y se hacen para preservación. Donar es totalmente voluntario y ayuda con los gastos que conlleva hacerlos
  (dispositivos de prueba, equipo, servidores). No hay nada de pago.

**¿Por qué no hay una versión nativa (NCE) como en los ports de Switch?** Los ports de Switch ejecutan el código del
propio juego directamente en el procesador del móvil, porque la Switch y los móviles Android usan la misma arquitectura
ARM de 64 bits. La 3DS no: su procesador es un ARM11 de **32 bits**. Una app de Android es un proceso de 64 bits y no
puede ejecutar código ARM de 32 bits; muchos móviles recientes ni siquiera pueden ejecutar código de 32 bits. Por eso
el código del juego tiene que **traducirse**: 3dsrecomp lo convierte en C y el compilador incluido en la app (Zig) lo
convierte en código nativo para tu móvil, una sola vez.

1. Copia al móvil tu `.cia` descifrado (o `.3ds`) de Persona Q2, versión de EE. UU.
2. Instala **PersonaQ2.apk** desde [Releases](../../releases) y ábrelo.
3. **1. Juego:** pulsa *Elegir archivo del juego* y elige tu `.cia`. El lanzador lo copia a la carpeta de la app
   (unos 2,9 GB); después puedes borrar tu copia.
4. **2. Traducción al español (opcional):** pulsa *Importar parche en español* y elige tu copia del parche de
   traducción (de Artur16211, HxCannon y Manuovi_07): su carpeta, la que tiene `code.bin`, `exheader.bin` y
   `romfs/Patch.cpk` (`luma/titles/00040000001D7100`) o cualquier carpeta por encima, o su `.zip`. Android no deja
   elegir la carpeta Download en sí, así que pon el parche en una carpeta propia (por ejemplo `Download/Persona Q2 Patch`).
5. **3. Texturas HD (opcional):** importa un pack de texturas HD en `.zip` con el formato de Zakuro, si tienes uno.
6. Pulsa **JUGAR**. La primera vez la app prepara su compilador y **traduce el juego para tu móvil**: deja la app
   abierta y en primer plano hasta que termine (unos 75 minutos en una Lenovo Tab M11, Snapdragon 685; menos en móviles
   más rápidos). Después el juego arranca al momento. Instalar, cambiar o quitar el parche vuelve a traducir el juego,
   porque el parche cambia su código.

No se proporcionan juegos, parches ni claves.

## Credits and license

- Runtime: [Zakuro](https://github.com/fearkov/zakuro) by fearkov, MIT, here with this port's changes (`zakuro/`).
- Recompiler: [3dsrecomp](https://github.com/fearkov/3dsrecomp) by fearkov, MIT, here with this port's changes
  (`3dsrecomp/`).
- C compiler on the phone: [Zig](https://ziglang.org/) 0.17.0 (MIT; LLVM/clang inside, Apache-2.0 with LLVM
  exceptions), shipped unmodified.
- Spanish fan translation (not included; the launcher imports your copy): Artur16211, HxCannon and Manuovi_07.
- This port's own code is MIT, see [LICENSE](LICENSE); third-party notices in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
