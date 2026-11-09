package com.pq2port.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compatibilidad con los distintos procesadores y GPUs: Snapdragon (Adreno),
 * MediaTek (Mali / PowerVR), Exynos (Mali / Xclipse), Tensor (Mali / PowerVR)
 * y Kirin (Mali / Maleoon).
 *
 * Zakuro detecta solo qué le falta a la GPU de Vulkan 1.3 y usa respaldos
 * (render passes, barreras de Vulkan 1.0, estado de profundidad en los
 * pipelines, D32S8, depth clamp en la CPU). Aquí se elige además un modo:
 * automático, compatibilidad (todos los respaldos aunque el driver diga que
 * tiene lo nuevo, para drivers con errores) o software (sin GPU para el 3D).
 */
final class Compat {
    static final String AUTO = "auto";
    static final String SAFE = "compat";
    static final String SOFTWARE = "software";

    private static final String PREFS = "compat";
    private static final String KEY_MODE = "gpu_mode";

    private Compat() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String mode(Context context) {
        return prefs(context).getString(KEY_MODE, AUTO);
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    static String modeLabel(String mode) {
        switch (mode) {
            case SAFE: return "Compatibilidad (respaldos para drivers con errores)";
            case SOFTWARE: return "Software (3D en la CPU, sin texturas HD)";
            default: return "Automático (recomendado)";
        }
    }

    /** Las variables que el juego pone en su entorno antes de arrancar. */
    static List<String[]> environment(Context context) {
        List<String[]> pairs = new ArrayList<>();
        String mode = mode(context);
        if (SAFE.equals(mode)) {
            pairs.add(new String[] {"ZAKURO_VK_COMPAT", "all"});
        } else if (SOFTWARE.equals(mode)) {
            pairs.add(new String[] {"ZAKURO_RENDERER", "software"});
        }
        // la resolución del 3D: automática según la GPU, o la escala elegida
        pairs.add(new String[] {"ZAKURO_RESOLUTION", Options.environmentValue(context)});
        // 30 o 60 fotogramas por segundo
        pairs.add(new String[] {"ZAKURO_FPS", String.valueOf(Options.frameRate(context))});

        // Controlador de GPU personalizado (libadrenotools / Turnip)
        File driverDir = new File(context.getFilesDir(), "gpu_driver");
        File activeFile = new File(driverDir, "active.txt");
        if (activeFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(activeFile))) {
                String active = r.readLine();
                if (active != null && !active.trim().isEmpty()) {
                    pairs.add(new String[] {"ZAKURO_HOOK_LIB_DIR", context.getApplicationInfo().nativeLibraryDir});
                    pairs.add(new String[] {"ZAKURO_CUSTOM_DRIVER_DIR", driverDir.getAbsolutePath()});
                    pairs.add(new String[] {"ZAKURO_CUSTOM_DRIVER_LIB", active.trim()});
                }
            } catch (Exception ignored) {
            }
        }
        // lo que se quiera probar a mano: zakuro/env.txt, CLAVE=valor por línea
        File file = new File(Paths.data(context), "env.txt");
        if (file.isFile()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    int equals = line.indexOf('=');
                    if (line.isEmpty() || line.startsWith("#") || equals <= 0) continue;
                    pairs.add(new String[] {line.substring(0, equals).trim(), line.substring(equals + 1).trim()});
                }
            } catch (Exception ignored) {
            }
        }
        return pairs;
    }

    /** El fabricante del procesador, para mostrarlo y para los avisos. */
    static String socVendor() {
        String text = (soc() + " " + Build.HARDWARE + " " + Build.BOARD).toLowerCase(Locale.ROOT);
        if (text.contains("qualcomm") || text.contains("qcom") || text.contains("snapdragon") || text.matches(".*\\b(sm|sdm|msm)\\d.*")) return "Qualcomm Snapdragon";
        if (text.contains("mediatek") || text.contains("mt6") || text.contains("mt8") || text.contains("dimensity") || text.contains("helio")) return "MediaTek";
        if (text.contains("google") || text.contains("tensor") || text.contains("gs101") || text.contains("gs201") || text.contains("zuma") || text.contains("zumapro") || text.contains("laguna")) return "Google Tensor";
        if (text.contains("samsung") || text.contains("exynos") || text.contains("s5e") || text.contains("universal")) return "Samsung Exynos";
        if (text.contains("hisilicon") || text.contains("kirin") || text.contains("hi36") || text.contains("hi62")) return "HiSilicon Kirin";
        if (text.contains("unisoc") || text.contains("spreadtrum") || text.contains("ums9") || text.contains("ums5")) return "Unisoc";
        return "desconocido";
    }

    /** El modelo del procesador tal como lo dice Android. */
    static String soc() {
        if (Build.VERSION.SDK_INT >= 31) {
            String manufacturer = Build.SOC_MANUFACTURER;
            String model = Build.SOC_MODEL;
            if (model != null && !model.isEmpty() && !Build.UNKNOWN.equals(model)) {
                return (manufacturer == null || Build.UNKNOWN.equals(manufacturer) ? "" : manufacturer + " ") + model;
            }
        }
        String platform = property("ro.board.platform");
        return platform.isEmpty() ? Build.HARDWARE : platform;
    }

    private static String property(String name) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, name);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- detección de cierres al arrancar ----

    private static File marker(Context context) {
        return new File(Paths.data(context), "launch_state");
    }

    /** El juego está arrancando: si el proceso muere antes de mark(ok), quedó "start". */
    static void markStarting(Context context) {
        write(marker(context), "start");
    }

    static void markRunning(Context context) {
        write(marker(context), "ok");
    }

    /** Salida normal o el juego quedó en segundo plano: nada que avisar. */
    static void clearMark(Context context) {
        marker(context).delete();
    }

    /** Si la última vez el juego se cerró solo mientras arrancaba. */
    static boolean crashedOnStart(Context context) {
        File file = marker(context);
        if (!file.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line != null && line.trim().equals("start");
        } catch (Exception e) {
            return false;
        }
    }

    private static void write(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
