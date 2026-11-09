package com.pq2port.app;

import android.content.Context;

import java.io.File;

/** Dónde guarda la app el juego, los datos de Zakuro, el parche y el pack HD. */
final class Paths {
    static final String TITLE_ID = "00040000001D7100";

    private Paths() {}

    /** Carpeta propia en el almacenamiento externo, visible por USB en Android/data. */
    static File base(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    static File data(Context context) {
        return new File(base(context), "zakuro");
    }

    static File gameDir(Context context) {
        return new File(base(context), "game");
    }

    static File mod(Context context) {
        return new File(new File(data(context), "mods"), TITLE_ID);
    }

    static File textures(Context context) {
        return new File(mod(context), "textures");
    }

    // the player's Spanish patch, laid out as Zakuro reads it: code.bin and exheader.bin in
    // mods/<title>, the patch's Patch.cpk as romfs/mod.cpk

    static File patchCode(Context context) {
        return new File(mod(context), "code.bin");
    }

    static File patchExheader(Context context) {
        return new File(mod(context), "exheader.bin");
    }

    static File patchCpk(Context context) {
        return new File(new File(mod(context), "romfs"), "mod.cpk");
    }

    /** where 3dsrecomp installs the recompiled game (XDG_DATA_HOME/3dsrecomp), internal, as external storage is noexec. */
    static File recompiled(Context context) {
        return new File(context.getFilesDir(), "3dsrecomp");
    }

    static File recompiledLibrary(Context context) {
        return new File(recompiled(context), TITLE_ID + ".so");
    }

    /**
     * deletes the recompiled game and what goes next to it under its name (the .so.new being installed, the
     * .hints Zakuro writes), so the next start recompiles the game's code as the patch now has it.
     */
    static void forgetRecompiled(Context context) {
        File[] files = recompiled(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().toUpperCase(java.util.Locale.ROOT).startsWith(TITLE_ID + ".")) file.delete();
        }
    }

    static File saves(Context context) {
        return new File(data(context), "user");
    }

    /** El archivo del juego: el importado, o uno que el usuario copió por USB. */
    static File game(Context context) {
        for (File dir : new File[] {gameDir(context), base(context)}) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName().toLowerCase();
                if (file.isFile() && file.length() > 1024 * 1024
                        && (name.endsWith(".cia") || name.endsWith(".3ds") || name.endsWith(".cci") || name.endsWith(".cxi"))) {
                    return file;
                }
            }
        }
        return null;
    }

    static int countPngs(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) count += countPngs(file);
            else if (file.getName().toLowerCase().endsWith(".png")) count++;
        }
        return count;
    }

    static void deleteTree(File file) {
        File[] files = file.listFiles();
        if (files != null) for (File child : files) deleteTree(child);
        file.delete();
    }
}
