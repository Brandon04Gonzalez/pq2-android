package com.pq2port.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The C compiler the game is recompiled with on the phone the first time it starts, as the APK holds no game
 * code: Zig 0.17.0 as lib/arm64-v8a/libzig.so (extracted at install, so it is a file that can run), and its
 * library (clang's and musl's headers, Zig's std and compiler_rt) in assets/zig-lib.zip, unpacked once into
 * files/zig/lib.
 */
final class Compiler {
    private static final String TAG = "PQ2";
    private static final String ASSET = "zig-lib.zip";
    /** the version of the library unpacked into files/zig/lib; another one is unpacked again. */
    static final String VERSION = "0.17.0-1";

    interface Progress {
        void at(long done, long total);
    }

    private Compiler() {}

    static File lib(Context context) {
        return new File(context.getFilesDir(), "zig/lib");
    }

    static File binary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libzig.so");
    }

    /** whether files/zig/lib holds this version of the compiler's library. */
    static boolean unpacked(Context context) {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(lib(context), ".ready")))) {
            return VERSION.equals(reader.readLine());
        } catch (IOException e) {
            return false;
        }
    }

    /** unpacks assets/zig-lib.zip into files/zig/lib; the version note goes last, so a cut unpack is redone. */
    static void unpack(Context context, Progress progress) throws IOException {
        File lib = lib(context);
        Paths.deleteTree(lib);
        if (!lib.mkdirs()) throw new IOException("could not create " + lib);
        String root = lib.getCanonicalPath() + File.separator;
        long total = -1;
        // stored uncompressed in the APK, so it has a length to show progress against
        try (AssetFileDescriptor fd = context.getAssets().openFd(ASSET)) {
            total = fd.getLength();
        } catch (IOException e) {
            Log.i(TAG, "zig-lib.zip is compressed in the APK, no progress", e);
        }
        Launcher.CountingStream counted = new Launcher.CountingStream(new BufferedInputStream(context.getAssets().open(ASSET), 1 << 16));
        byte[] buffer = new byte[1 << 16];
        long shown = 0;
        try (ZipInputStream zip = new ZipInputStream(counted)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(lib, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream file = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) file.write(buffer, 0, read);
                }
                if (progress != null && counted.count - shown > (1 << 20)) {
                    shown = counted.count;
                    progress.at(counted.count, total);
                }
            }
        }
        try (OutputStream note = new FileOutputStream(new File(lib, ".ready"))) {
            note.write((VERSION + "\n").getBytes("UTF-8"));
        }
    }

    /**
     * what Zakuro recompiles the game with (zakuro lib.rs recompile_on_phone), set before the native library
     * starts. Without the compiler nothing is set, and the game runs interpreted.
     */
    static void apply(Context context) {
        boolean ready = unpacked(context) && binary(context).isFile();
        String lib = lib(context).getPath();
        String include = lib + "/libc/include/";
        String cache = new File(context.getCacheDir(), "zig").getPath();
        set("ZAKURO_CC", ready ? binary(context).getPath() : null);
        // no libc is linked (the translated code calls none), but -nostdlib also leaves out musl's headers,
        // which recomp.h needs for math.h, stdint.h and string.h
        set("ZAKURO_CC_ARGS", ready ? "cc -target aarch64-linux-musl -nostdlib -isystem " + include + "aarch64-linux-musl -isystem "
            + include + "generic-musl -isystem " + include + "any-linux-any -Wl,-z,max-page-size=16384" : null);
        set("ZIG_LIB_DIR", ready ? lib : null);
        set("ZIG_GLOBAL_CACHE_DIR", ready ? cache : null);
        set("ZIG_LOCAL_CACHE_DIR", ready ? cache : null);
        Log.i(TAG, ready ? "compiler: " + binary(context) : "no compiler: the game runs interpreted");
    }

    private static void set(String name, String value) {
        try {
            if (value == null) {
                Os.unsetenv(name);
            } else {
                Os.setenv(name, value, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "setenv " + name, e);
        }
    }
}
