package com.pq2port.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Los ajustes del jugador: a qué resolución se dibuja el 3D, el contador de
 * FPS y el tamaño y la opacidad de los controles táctiles.
 *
 * Con la resolución automática Zakuro empieza con la escala que corresponde
 * a la GPU (por su nombre) y, si mientras se juega la GPU no llega a la
 * velocidad completa, baja un paso en el momento y lo recuerda para esa GPU
 * en zakuro/resolution.txt.
 */
final class Options {
    /** resolución automática; si no, la escala fija 1..4. */
    static final int AUTO = 0;
    static final int[] RESOLUTIONS = {AUTO, 1, 2, 3, 4};
    static final float[] SIZES = {0.8f, 0.9f, 1f, 1.1f, 1.2f};
    static final float[] OPACITIES = {0.3f, 0.5f, 0.75f, 1f};

    private static final String PREFS = "options";

    private Options() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int resolution(Context context) {
        return prefs(context).getInt("resolution", AUTO);
    }

    /** volver a elegir la automática olvida lo aprendido: empieza otra vez por la escala de la GPU. */
    static void setResolution(Context context, int value) {
        prefs(context).edit().putInt("resolution", value).apply();
        if (value == AUTO) learnedFile(context).delete();
    }

    static String resolutionLabel(int value) {
        switch (value) {
            case 1: return "1x · 400×240, la más rápida";
            case 2: return "2x · 800×480";
            case 3: return "3x · 1200×720";
            case 4: return "4x · 1600×960, solo GPUs de gama alta";
            default: return "Automática según la GPU (recomendada)";
        }
    }

    /** lo que muestra el lanzador: la elegida, y con la automática la escala que se usa en esta GPU. */
    static String resolutionSummary(Context context) {
        int value = resolution(context);
        if (value != AUTO) return value + "x";
        int learned = learned(context);
        return learned > 0 ? String.format(Locale.US, "Automática (%dx en esta GPU)", learned) : "Automática";
    }

    private static File learnedFile(Context context) {
        return new File(Paths.data(context), "resolution.txt");
    }

    /** la escala que Zakuro recordó para la GPU, 0 si todavía no bajó ninguna. */
    static int learned(Context context) {
        File file = learnedFile(context);
        if (!file.isFile()) return 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("scale=")) return Integer.parseInt(line.substring(6).trim());
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    static String environmentValue(Context context) {
        int value = resolution(context);
        return value == AUTO ? "auto" : String.valueOf(value);
    }

    /** fotogramas por segundo: 30 deja sin dibujar uno de cada dos de un juego a 60; 30 por defecto. */
    static final int[] FRAME_RATES = {30, 60};

    static int frameRate(Context context) {
        return prefs(context).getInt("frame_rate", 30);
    }

    static void setFrameRate(Context context, int value) {
        prefs(context).edit().putInt("frame_rate", value).apply();
    }

    static String frameRateLabel(int value) {
        return value == 60
            ? "60 FPS: como la consola, para GPUs potentes"
            : "30 FPS (recomendado): la GPU dibuja la mitad y va más holgada";
    }

    static boolean showFps(Context context) {
        return prefs(context).getBoolean("show_fps", true);
    }

    static void setShowFps(Context context, boolean value) {
        prefs(context).edit().putBoolean("show_fps", value).apply();
    }

    static float controlsSize(Context context) {
        return prefs(context).getFloat("controls_size", 1f);
    }

    static void setControlsSize(Context context, float value) {
        prefs(context).edit().putFloat("controls_size", value).apply();
    }

    static float controlsOpacity(Context context) {
        return prefs(context).getFloat("controls_opacity", 1f);
    }

    static void setControlsOpacity(Context context, float value) {
        prefs(context).edit().putFloat("controls_opacity", value).apply();
    }

    static String percent(float value) {
        return Math.round(value * 100) + " %";
    }
}
