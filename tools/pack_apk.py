"""Builds the unsigned APK: aapt2's base.apk + classes.dex + libmain.so per ABI,
other libraries, and assets. libmain.so goes uncompressed; an exe= library (the
Zig compiler as libzig.so) goes compressed, as Android extracts it at install
(extractNativeLibs) to run it; zip assets go uncompressed, being compressed already."""
import sys
import zipfile

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
libs = []      # (abi, ruta)
assets = []    # (nombre dentro de assets/, ruta)
extras = []    # (abi/nombre.so, ruta): otras librerías, como la capa de validación
exes = []      # (abi/name.so, path): programs shipped as libraries, compressed
for arg in sys.argv[4:]:
    kind, _, rest = arg.partition('=')
    name, _, path = rest.partition(':')
    {'lib': libs, 'asset': assets, 'extra': extras, 'exe': exes}[kind].append((name, path))

with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, 'w') as dst:
    for item in src.infolist():
        data = src.read(item.filename)
        info = zipfile.ZipInfo(item.filename, date_time=(2026, 10, 20, 0, 0, 0))
        info.compress_type = item.compress_type
        dst.writestr(info, data)
    dst.write(dex, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    for abi, path in libs:
        dst.write(path, f'lib/{abi}/libmain.so', compress_type=zipfile.ZIP_STORED)
    for name, path in extras:
        dst.write(path, f'lib/{name}', compress_type=zipfile.ZIP_STORED)
    for name, path in exes:
        dst.write(path, f'lib/{name}', compress_type=zipfile.ZIP_DEFLATED)
    for name, path in assets:
        dst.write(path, f'assets/{name}', compress_type=zipfile.ZIP_STORED if name.endswith('.zip') else zipfile.ZIP_DEFLATED)
print('apk sin firmar:', out)
