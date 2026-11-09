"""The parts of Zig 0.17.0's lib folder that `zig cc -nostdlib` needs to compile 3dsrecomp's C on a phone (clang's own
headers, musl's for aarch64, and Zig's std and compiler_rt), zipped for the APK's assets."""
import os
import sys
import zipfile

# the folder zig-aarch64-linux-0.17.0 was unpacked in (python make_zig_lib.py <folder>), this script's own by default
HERE = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, 'zig-aarch64-linux-0.17.0', 'lib')
KEEP_DIRS = ['include', 'std', 'compiler_rt', 'libc/include/generic-musl', 'libc/include/aarch64-linux-musl',
             'libc/include/any-linux-any']
KEEP_FILES = ['c.zig', 'compiler_rt.zig', 'ubsan_rt.zig', 'fuzzer.zig', 'zig.h']

out = os.path.join(HERE, 'zig-lib.zip')
n = 0
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for f in KEEP_FILES:
        z.write(os.path.join(ROOT, f), f)
        n += 1
    for d in KEEP_DIRS:
        for dp, _, fs in os.walk(os.path.join(ROOT, *d.split('/'))):
            for f in fs:
                full = os.path.join(dp, f)
                z.write(full, os.path.relpath(full, ROOT).replace(os.sep, '/'))
                n += 1
print(n, 'files', round(os.path.getsize(out) / 1e6, 1), 'MB')
