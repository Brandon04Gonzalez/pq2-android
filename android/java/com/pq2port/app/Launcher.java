package com.pq2port.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;

import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.database.Cursor;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Home screen: picks the game file (once), imports the HD texture pack and the
 * player's own Spanish patch, and unpacks the C compiler the game is recompiled
 * with on the phone before its first start.
 */
public class Launcher extends Activity {
    private static final int PICK_GAME = 1;
    private static final int PICK_PACK = 2;
    private static final int PICK_DRIVER = 3;
    private static final int PICK_ICON = 4;
    private static final int PICK_PATCH_FOLDER = 5;
    private static final int PICK_PATCH_ZIP = 6;
    private static final int RED = 0xFFE8261C;

    private TextView gameStatus, patchStatus, packStatus, progressText, deviceStatus, firstStartNote;
    private ProgressBar progress;
    private Button playButton, gameButton, packButton, removePackButton, modeButton, driverButton, patchButton, removePatchButton;
    private Button resolutionButton, frameRateButton, fpsButton, sizeButton, opacityButton, iconButton;
    private ImageView appIcon;
    /** el aviso de cierre al arrancar se muestra una vez por cierre. */
    private boolean crashAsked;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        offerCompatAfterCrash();
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(primary ? RED : 0xFF2A2A2A);
        button.setBackground(shape);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(primary ? 60 : 50));
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(22));
        root.setBackgroundColor(0xFF0B0B0B);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        appIcon = new ImageView(this);
        updateHeaderIcon();
        int iconSide = dp(46);
        header.addView(appIcon, new LinearLayout.LayoutParams(iconSide, iconSide));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(12), 0, 0, 0);
        TextView title = text("PERSONA Q2", 32, RED, true);
        title.setGravity(Gravity.START);
        titleCol.addView(title);
        titleCol.addView(text("New Cinema Labyrinth · port nativo para Android", 14, 0xFFDDDDDD, false));
        header.addView(titleCol);
        root.addView(header);
        root.addView(text("Tu juego se recompila a ARM64 en el propio móvil (3dsrecomp + Zig) sobre el runtime Zakuro · Texturas HD · Parche en español", 12, 0xFF888888, false));

        root.addView(section("1. Juego"));
        gameStatus = text("", 14, 0xFFCCCCCC, false);
        root.addView(gameStatus);
        gameButton = button("Elegir archivo del juego (.cia / .3ds)", false, v -> pick(PICK_GAME, "*/*"));
        root.addView(gameButton);

        root.addView(section("2. Traducción al español (opcional)"));
        patchStatus = text("", 14, 0xFFCCCCCC, false);
        root.addView(patchStatus);
        patchButton = button("", false, v -> choosePatch());
        root.addView(patchButton);
        removePatchButton = button("Quitar parche en español", false, v -> confirmRemovePatch());
        root.addView(removePatchButton);

        root.addView(section("3. Texturas HD (opcional)"));
        packStatus = text("", 14, 0xFFCCCCCC, false);
        root.addView(packStatus);
        packButton = button("Importar pack HD (PQ2_HD_Textures.zip)", false, v -> pick(PICK_PACK, "application/zip"));
        root.addView(packButton);
        removePackButton = button("Quitar pack HD", false, v -> confirmRemovePack());
        root.addView(removePackButton);

        root.addView(section("4. Procesador y GPU"));
        deviceStatus = text("", 14, 0xFFCCCCCC, false);
        root.addView(deviceStatus);
        modeButton = button("", false, v -> chooseMode());
        root.addView(modeButton);
        driverButton = button("", false, v -> chooseDriver());
        root.addView(driverButton);

        root.addView(section("5. Pantalla y controles"));
        resolutionButton = button("", false, v -> chooseResolution());
        root.addView(resolutionButton);
        frameRateButton = button("", false, v -> chooseFrameRate());
        root.addView(frameRateButton);
        fpsButton = button("", false, v -> {
            Options.setShowFps(this, !Options.showFps(this));
            refresh();
        });
        root.addView(fpsButton);
        sizeButton = button("", false, v -> chooseControlsSize());
        root.addView(sizeButton);
        opacityButton = button("", false, v -> chooseControlsOpacity());
        root.addView(opacityButton);
        iconButton = button("", false, v -> chooseIcon());
        root.addView(iconButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        progressParams.topMargin = dp(16);
        root.addView(progress, progressParams);
        progressText = text("", 13, 0xFFAAAAAA, false);
        root.addView(progressText);

        playButton = button("▶  JUGAR", true, v -> play());
        root.addView(playButton);
        firstStartNote = text("⏳ La primera vez que juegas (y después de cambiar el parche), el juego se traduce a código del móvil: "
            + "tarda un buen rato, alrededor de una hora y cuarto en una tableta de gama media (menos en móviles más rápidos), con el progreso en pantalla. "
            + "Deja la app abierta y en primer plano hasta que termine; después arranca enseguida.", 13, 0xFFE0B040, false);
        root.addView(firstStartNote);

        Button exportLogs = button("Exportar registros de depuración", false, v -> exportLogs());
        root.addView(exportLogs);

        root.addView(text(
            "Controles: pantalla táctil inferior del 3DS, D-pad, stick, A/B/X/Y, L/R, START/SELECT. "
            + "El botón ≡ (o Atrás) abre el menú: ajustes, guardar/cargar estado. Mantén pulsado ≡ para ocultar los controles. "
            + "Con la resolución automática, si tu GPU no llega a 60 FPS el juego baja un paso solo y lo recuerda. "
            + "Un mando Bluetooth también funciona.\n\nTus partidas se guardan en Android/data/com.pq2port.app/files/zakuro/user "
            + "— haz copia antes de desinstalar la app.", 12, 0xFF777777, false));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0B0B0B);
        scroll.addView(root);
        return scroll;
    }

    private TextView section(String label) {
        TextView view = text(label, 17, Color.WHITE, true);
        view.setPadding(0, dp(18), 0, dp(2));
        return view;
    }

    private void refresh() {
        File game = Paths.game(this);
        gameStatus.setText(game != null
            ? "✔ " + game.getName() + String.format(Locale.US, " (%.2f GB)", game.length() / 1e9)
            : "✘ Falta el juego. Elige tu copia de Persona Q2 (USA): el .cia descifrado o .3ds. Se copia una sola vez a la carpeta de la app.");
        String patch = patchSummary();
        patchStatus.setText(patch != null
            ? "✔ Parche en español instalado: " + patch
            : "Sin parche: el juego sale en inglés. Importa tu copia del parche en español (Artur16211 · HxCannon · Manuovi_07): "
                + "su carpeta (luma/titles/" + Paths.TITLE_ID + ", con code.bin, exheader.bin y romfs/Patch.cpk) o su .zip.");
        patchButton.setText(patch != null ? "Cambiar parche en español (carpeta o .zip)" : "Importar parche en español (carpeta o .zip)");
        removePatchButton.setVisibility(patch != null ? View.VISIBLE : View.GONE);
        firstStartNote.setVisibility(Paths.recompiledLibrary(this).isFile() ? View.GONE : View.VISIBLE);
        int pngs = Paths.countPngs(Paths.textures(this));
        packStatus.setText(pngs > 0
            ? "✔ Pack HD instalado: " + pngs + " texturas reescaladas con IA"
            : "Sin pack HD: el juego usa las texturas originales. Importa PQ2_HD_Textures.zip para verlas en alta calidad.");
        removePackButton.setVisibility(pngs > 0 ? View.VISIBLE : View.GONE);
        deviceStatus.setText(deviceText());
        modeButton.setText("Modo de GPU: " + Compat.modeLabel(Compat.mode(this)));
        driverButton.setText("Controlador GPU: " + activeDriverName());
        resolutionButton.setText("Resolución del 3D: " + Options.resolutionSummary(this));
        frameRateButton.setText("Fotogramas por segundo: " + Options.frameRate(this));
        fpsButton.setText("Contador de FPS: " + (Options.showFps(this) ? "sí" : "no"));
        sizeButton.setText("Tamaño de los controles: " + Options.percent(Options.controlsSize(this)));
        opacityButton.setText("Opacidad de los controles: " + Options.percent(Options.controlsOpacity(this)));
        File customIcon = new File(getFilesDir(), "custom_icon.png");
        iconButton.setText("Icono de la app: " + (customIcon.exists() ? "Personalizado (cambiar/restablecer)" : "Subir imagen personalizada"));
        updateHeaderIcon();
        boolean ready = game != null && !busy;
        playButton.setEnabled(ready);
        playButton.setAlpha(ready ? 1f : 0.4f);
        gameButton.setEnabled(!busy);
        packButton.setEnabled(!busy);
        patchButton.setEnabled(!busy);
        removePatchButton.setEnabled(!busy);
        driverButton.setEnabled(!busy);
    }

    /** El procesador, y la GPU tal como la vio Zakuro la última vez que corrió el juego. */
    private String deviceText() {
        StringBuilder text = new StringBuilder();
        text.append("Procesador: ").append(Compat.socVendor()).append(" · ").append(Compat.soc())
            .append("\nNúcleos: ").append(Runtime.getRuntime().availableProcessors());
        File report = new File(Paths.data(this), "gpu.txt");
        String gpu = report.isFile() ? readText(report, 4096).trim() : "";
        if (gpu.isEmpty()) {
            text.append("\nGPU: se detecta la primera vez que juegas.");
        } else {
            String[] lines = gpu.split("\n", 2);
            text.append("\nGPU: ").append(lines[0].trim());
            if (lines.length > 1) {
                String how = lines[1].trim();
                text.append(how.startsWith("software")
                    ? "\n⚠ Esta GPU no puede dibujar el 3D: el juego usa la CPU (sin texturas HD). " + how
                    : "\n✔ 3D en la GPU (" + how + ")");
            }
        }
        return text.toString();
    }

    private void chooseMode() {
        final String[] modes = {Compat.AUTO, Compat.SAFE, Compat.SOFTWARE};
        String[] labels = new String[modes.length];
        int current = 0;
        for (int i = 0; i < modes.length; i++) {
            labels[i] = Compat.modeLabel(modes[i]);
            if (modes[i].equals(Compat.mode(this))) current = i;
        }
        new AlertDialog.Builder(this)
            .setTitle("Modo de GPU")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                Compat.setMode(this, modes[which]);
                dialog.dismiss();
                refresh();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private File driverDir() {
        File dir = new File(getFilesDir(), "gpu_driver");
        dir.mkdirs();
        return dir;
    }

    private String activeDriverName() {
        File activeFile = new File(driverDir(), "active.txt");
        if (activeFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(activeFile))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            } catch (Exception ignored) {}
        }
        return "del sistema";
    }

    private void chooseDriver() {
        String active = activeDriverName();
        boolean hasCustom = !"del sistema".equals(active);
        String[] options = hasCustom 
            ? new String[] {"Instalar controlador (.zip / .so)", "Volver al controlador del sistema (Quitar)"}
            : new String[] {"Instalar controlador (.zip / .so)"};

        new AlertDialog.Builder(this)
            .setTitle("Controlador de GPU (Adreno Tools)")
            .setMessage("Activo: " + active + "\n\nPermite usar controladores Turnip / Mesa personalizados en dispositivos Qualcomm Snapdragon para solucionar fallos gráficos o cierres.")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    pickDriver();
                } else {
                    removeDriver();
                }
            })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private void pickDriver() {
        pick(PICK_DRIVER, "*/*");
    }

    private void removeDriver() {
        File dir = driverDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        refresh();
        Toast.makeText(this, "Controlador personalizado eliminado. Se usará el del sistema.", Toast.LENGTH_SHORT).show();
    }

    private static void copyDriverStream(InputStream in, File out) throws IOException {
        try (OutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }

    private void importDriver(Uri uri) {
        setBusy(true, "Instalando controlador de GPU…");
        new Thread(() -> {
            try {
                String picked = displayName(uri, "driver.zip");
                File dir = driverDir();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                String libName = null;
                if (picked.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    String soEntry = null, wanted = null;
                    try (ZipInputStream zin = new ZipInputStream(getContentResolver().openInputStream(uri))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            String base = new File(e.getName()).getName();
                            if (e.isDirectory()) continue;
                            if (base.equals("meta.json")) {
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                                        .matcher(bo.toString("UTF-8"));
                                if (m.find()) wanted = m.group(1);
                            } else if (base.endsWith(".so")) {
                                copyDriverStream(zin, new File(dir, base));
                                if (soEntry == null) soEntry = base;
                            }
                        }
                    }
                    libName = wanted != null && new File(dir, wanted).exists() ? wanted : soEntry;
                } else {
                    libName = picked.endsWith(".so") ? picked : picked + ".so";
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("No se pudo abrir el archivo seleccionado");
                        copyDriverStream(in, new File(dir, libName));
                    }
                }
                if (libName == null) throw new IOException("No se encontró ninguna librería .so dentro del archivo");
                try (FileWriter w = new FileWriter(new File(dir, "active.txt"))) {
                    w.write(libName + "\n");
                }
                final String finalLibName = libName;
                runOnUiThread(() -> {
                    refresh();
                    new AlertDialog.Builder(this)
                            .setTitle("Controlador importado")
                            .setMessage(finalLibName + "\n\nSe ha activado correctamente.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Error al importar controlador")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("OK", null)
                            .show();
                });
            } finally {
                setBusy(false, "");
            }
        }).start();
    }

    private void updateHeaderIcon() {
        if (appIcon == null) return;
        File custom = new File(getFilesDir(), "custom_icon.png");
        if (custom.exists()) {
            Bitmap b = BitmapFactory.decodeFile(custom.getAbsolutePath());
            if (b != null) {
                appIcon.setImageBitmap(b);
                return;
            }
        }
        appIcon.setImageResource(R.mipmap.ic_launcher);
    }

    /** el icono de la app: subir imagen personalizada o restablecer. */
    private void chooseIcon() {
        File custom = new File(getFilesDir(), "custom_icon.png");
        if (custom.exists()) {
            new AlertDialog.Builder(this)
                .setTitle("Icono de la app")
                .setItems(new String[] {"Subir nueva imagen", "Restablecer icono original", "Crear acceso directo en pantalla de inicio"}, (dialog, which) -> {
                    if (which == 0) {
                        pick(PICK_ICON, "image/*");
                    } else if (which == 1) {
                        custom.delete();
                        refresh();
                        Toast.makeText(this, "Icono restablecido al predeterminado", Toast.LENGTH_SHORT).show();
                    } else if (which == 2) {
                        pinShortcut();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
        } else {
            pick(PICK_ICON, "image/*");
        }
    }

    private void onIconPicked(Uri uri) {
        try {
            Bitmap src;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) throw new IOException("No se pudo decodificar la imagen");
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap icon = Bitmap.createScaledBitmap(square, 432, 432, true);
            File custom = new File(getFilesDir(), "custom_icon.png");
            try (FileOutputStream out = new FileOutputStream(custom)) {
                icon.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            refresh();
            pinShortcut();
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo usar esa imagen: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pinShortcut() {
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, "Imagen guardada.", Toast.LENGTH_LONG).show();
                return;
            }
            File custom = new File(getFilesDir(), "custom_icon.png");
            Bitmap icon = custom.exists() ? BitmapFactory.decodeFile(custom.getAbsolutePath()) : null;
            if (icon == null) return;
            ShortcutInfo pin = new ShortcutInfo.Builder(this, "custom-icon")
                .setIntent(new Intent(Intent.ACTION_MAIN).setClass(this, Launcher.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                .setShortLabel("PQ2").setLongLabel("Persona Q2")
                .setIcon(Icon.createWithAdaptiveBitmap(icon))
                .build();
            sm.requestPinShortcut(pin, null);
            Toast.makeText(this, "Acepta el aviso para poner el acceso directo con tu imagen en la pantalla de inicio.", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Error al crear acceso directo: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseFrameRate() {
        int[] values = Options.FRAME_RATES;
        String[] labels = new String[values.length];
        int current = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = Options.frameRateLabel(values[i]);
            if (values[i] == Options.frameRate(this)) current = i;
        }
        new AlertDialog.Builder(this)
            .setTitle("Fotogramas por segundo")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                Options.setFrameRate(this, values[which]);
                dialog.dismiss();
                refresh();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void chooseResolution() {
        int[] values = Options.RESOLUTIONS;
        String[] labels = new String[values.length];
        int current = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = Options.resolutionLabel(values[i]);
            if (values[i] == Options.resolution(this)) current = i;
        }
        new AlertDialog.Builder(this)
            .setTitle("Resolución del 3D")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                Options.setResolution(this, values[which]);
                dialog.dismiss();
                refresh();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void chooseControlsSize() {
        choosePercent("Tamaño de los controles", Options.SIZES, Options.controlsSize(this), value -> Options.setControlsSize(this, value));
    }

    private void chooseControlsOpacity() {
        choosePercent("Opacidad de los controles", Options.OPACITIES, Options.controlsOpacity(this), value -> Options.setControlsOpacity(this, value));
    }

    private interface FloatChoice {
        void chosen(float value);
    }

    private void choosePercent(String title, float[] values, float now, FloatChoice choice) {
        String[] labels = new String[values.length];
        int current = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = Options.percent(values[i]);
            if (Math.abs(values[i] - now) < 0.01f) current = i;
        }
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                choice.chosen(values[which]);
                dialog.dismiss();
                refresh();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** Si el juego se cerró mientras arrancaba, casi siempre es el driver de la GPU: ofrece un modo más seguro. */
    private void offerCompatAfterCrash() {
        if (crashAsked || !Compat.crashedOnStart(this)) return;
        crashAsked = true;
        Compat.clearMark(this);
        String mode = Compat.mode(this);
        if (Compat.SOFTWARE.equals(mode)) return;
        final String next = Compat.AUTO.equals(mode) ? Compat.SAFE : Compat.SOFTWARE;
        new AlertDialog.Builder(this)
            .setTitle("El juego se cerró al arrancar")
            .setMessage("Puede ser un problema del driver de la GPU de tu teléfono (" + Compat.socVendor() + ").\n\n"
                + "¿Probar el modo «" + Compat.modeLabel(next) + "»? Puedes cambiarlo cuando quieras en «4. Procesador y GPU».")
            .setPositiveButton("Probar", (d, w) -> {
                Compat.setMode(this, next);
                refresh();
            })
            .setNegativeButton("Dejarlo así", null)
            .show();
    }

    private static String readText(File file, int most) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[most];
            int read = in.read(bytes);
            return read > 0 ? new String(bytes, 0, read, "UTF-8") : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void pick(int code, String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        startActivityForResult(intent, code);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_GAME) importGame(uri);
        else if (requestCode == PICK_PACK) importPack(uri);
        else if (requestCode == PICK_DRIVER) importDriver(uri);
        else if (requestCode == PICK_ICON) onIconPicked(uri);
        else if (requestCode == PICK_PATCH_FOLDER) importPatchFolder(uri);
        else if (requestCode == PICK_PATCH_ZIP) importPatchZip(uri);
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private long size(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        runOnUiThread(() -> {
            progress.setVisibility(value ? View.VISIBLE : View.GONE);
            progressText.setText(message);
            refresh();
        });
    }

    private void showProgress(long done, long total, String what) {
        runOnUiThread(() -> {
            progress.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
            progressText.setText(String.format(Locale.US, "%s %.0f / %.0f MB", what, done / 1e6, total / 1e6));
        });
    }

    private void error(String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Persona Q2").setMessage(message).setPositiveButton("OK", null).show());
    }

    /** Copia el juego a la carpeta de la app, comprobando que sea Persona Q2. */
    private void importGame(Uri uri) {
        String name = displayName(uri, "PersonaQ2.cia");
        long total = size(uri);
        StatFs stat = new StatFs(Paths.base(this).getPath());
        if (total > 0 && stat.getAvailableBytes() < total + 200_000_000L) {
            error(String.format(Locale.US, "No hay espacio suficiente: hacen falta %.1f GB libres.", total / 1e9));
            return;
        }
        setBusy(true, "Copiando el juego…");
        new Thread(() -> {
            File dir = Paths.gameDir(this);
            dir.mkdirs();
            File partial = new File(dir, "copiando.tmp");
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(partial)) {
                byte[] header = new byte[Rom.HEADER];
                byte[] buffer = new byte[4 << 20];
                long done = 0;
                int read;
                long lastShown = 0;
                while ((read = in.read(buffer)) > 0) {
                    if (done < header.length) System.arraycopy(buffer, 0, header, (int) done, (int) Math.min(read, header.length - done));
                    out.write(buffer, 0, read);
                    done += read;
                    if (done - lastShown > (16 << 20)) {
                        lastShown = done;
                        showProgress(done, total, "Copiando el juego");
                    }
                }
                out.flush();
                String problem = Rom.check(header, done);
                if (problem != null) {
                    partial.delete();
                    error(problem);
                } else {
                    // solo un juego a la vez
                    File[] old = dir.listFiles();
                    if (old != null) for (File file : old) if (!file.equals(partial)) file.delete();
                    String clean = name.replaceAll("[^A-Za-z0-9._ ()-]", "_");
                    if (!partial.renameTo(new File(dir, clean))) throw new IOException("no se pudo renombrar");
                }
            } catch (Exception e) {
                partial.delete();
                error("No se pudo copiar el juego: " + e.getMessage());
            }
            setBusy(false, "");
        }).start();
    }

    /** Extrae el zip del pack HD a mods/<título>/textures. */
    private void importPack(Uri uri) {
        long total = size(uri);
        setBusy(true, "Instalando el pack HD…");
        new Thread(() -> {
            File dir = Paths.textures(this);
            File staging = new File(Paths.mod(this), "textures.new");
            Paths.deleteTree(staging);
            staging.mkdirs();
            int count = 0;
            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                CountingStream counted = new CountingStream(new BufferedInputStream(raw, 1 << 20));
                ZipInputStream zip = new ZipInputStream(counted);
                ZipEntry entry;
                byte[] buffer = new byte[1 << 20];
                long lastShown = 0;
                String canonical = staging.getCanonicalPath() + File.separator;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String entryName = entry.getName();
                    String lower = entryName.toLowerCase();
                    if (!lower.endsWith(".png") && !lower.endsWith("pack.json")) continue;
                    // aplana las carpetas: Zakuro busca en todas las subcarpetas igual
                    File out = new File(staging, new File(entryName).getName());
                    if (!out.getCanonicalPath().startsWith(canonical)) continue;
                    try (OutputStream file = new FileOutputStream(out)) {
                        int read;
                        while ((read = zip.read(buffer)) > 0) file.write(buffer, 0, read);
                    }
                    if (lower.endsWith(".png")) count++;
                    if (counted.count - lastShown > (16 << 20)) {
                        lastShown = counted.count;
                        showProgress(counted.count, total, "Instalando el pack HD");
                    }
                }
                if (count == 0) throw new IOException("el zip no tiene texturas .png");
                Paths.deleteTree(dir);
                if (!staging.renameTo(dir)) throw new IOException("no se pudo mover el pack");
            } catch (Exception e) {
                Paths.deleteTree(staging);
                error("No se pudo instalar el pack HD: " + e.getMessage());
            }
            setBusy(false, count > 0 ? "Pack HD instalado: " + count + " texturas" : "");
        }).start();
    }

    private void confirmRemovePack() {
        new AlertDialog.Builder(this)
            .setTitle("Quitar pack HD")
            .setMessage("¿Borrar las texturas HD? El juego volverá a las originales.")
            .setPositiveButton("Borrar", (d, w) -> {
                Paths.deleteTree(Paths.textures(this));
                refresh();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ---- the Spanish patch: the player's own files, imported here, never shipped in the APK ----

    /** what of the patch is installed ("code.bin, exheader.bin, mod.cpk (88.6 MB)"), or null when none of it is. */
    private String patchSummary() {
        StringBuilder found = new StringBuilder();
        for (File file : new File[] {Paths.patchCode(this), Paths.patchExheader(this), Paths.patchCpk(this)}) {
            if (!file.isFile()) continue;
            if (found.length() > 0) found.append(", ");
            found.append(file.getName());
        }
        if (found.length() == 0) return null;
        File cpk = Paths.patchCpk(this);
        if (cpk.isFile()) found.append(String.format(Locale.US, " (%.1f MB)", cpk.length() / 1e6));
        return found.toString();
    }

    private void choosePatch() {
        new AlertDialog.Builder(this)
            .setTitle("Parche en español")
            .setMessage("Elige tu copia del parche en español de Persona Q2:\n\n"
                + "• Su carpeta: la que tiene code.bin, exheader.bin y romfs/Patch.cpk (luma/titles/" + Paths.TITLE_ID
                + "), o cualquier carpeta por encima, como luma o la del parche descomprimido.\n"
                + "• O el .zip del parche, tal cual.")
            .setPositiveButton("Carpeta", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_PATCH_FOLDER))
            .setNeutralButton(".zip", (d, w) -> pick(PICK_PATCH_ZIP, "application/zip"))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void confirmRemovePatch() {
        new AlertDialog.Builder(this)
            .setTitle("Quitar parche en español")
            .setMessage("¿Borrar el parche? El juego volverá al inglés, y se traducirá de nuevo para el móvil la próxima vez que juegues.")
            .setPositiveButton("Borrar", (d, w) -> {
                removePatchFiles();
                Paths.forgetRecompiled(this);
                refresh();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** whether a file of what the player picked may be one of the patch's, by its name. */
    private static boolean patchFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("code.bin") || lower.equals("exheader.bin") || lower.equals("patch.cpk") || lower.equals("mod.cpk");
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** a path's rank as the patch's code.bin, lowest first: in this game's title folder, then the least deep. */
    private static int patchRank(String path) {
        String[] parts = path.split("/");
        boolean titleFolder = parts.length > 1 && parts[parts.length - 2].equalsIgnoreCase(Paths.TITLE_ID);
        return (titleFolder ? 0 : 1000) + parts.length;
    }

    private static String findPath(Iterable<String> paths, String wanted) {
        for (String path : paths) if (path.equalsIgnoreCase(wanted)) return path;
        return null;
    }

    /**
     * the patch's code.bin, exheader.bin and cpk among the paths found in what the player picked: the folder
     * with code.bin, and that folder's exheader.bin and romfs/Patch.cpk or mod.cpk; null for those it lacks.
     * Null when there is no code.bin at all.
     */
    private static String[] choosePatchFiles(Iterable<String> paths) throws IOException {
        String code = null;
        for (String path : paths) {
            if (baseName(path).equalsIgnoreCase("code.bin") && (code == null || patchRank(path) < patchRank(code))) code = path;
        }
        if (code == null) return null;
        String folder = code.substring(0, code.length() - "code.bin".length());
        String title = folder.isEmpty() ? "" : baseName(folder.substring(0, folder.length() - 1));
        if (title.matches("[0-9A-Fa-f]{16}") && !title.equalsIgnoreCase(Paths.TITLE_ID)) {
            throw new IOException("ese parche es de otro juego (" + title + "), no de Persona Q2 (" + Paths.TITLE_ID + ").");
        }
        String cpk = null;
        for (String name : new String[] {"romfs/Patch.cpk", "romfs/mod.cpk", "Patch.cpk", "mod.cpk"}) {
            if (cpk == null) cpk = findPath(paths, folder + name);
        }
        return new String[] {code, findPath(paths, folder + "exheader.bin"), cpk};
    }

    private static String noPatchMessage(java.util.Collection<String> found) {
        StringBuilder text = new StringBuilder("no se encontró code.bin. Elige la carpeta del parche (la que tiene code.bin, exheader.bin y "
            + "romfs/Patch.cpk, p. ej. luma/titles/" + Paths.TITLE_ID + ", o una por encima) o su .zip.");
        if (!found.isEmpty()) text.append("\n\nSolo había: ").append(String.join(", ", found));
        return text.toString();
    }

    /** the files under a picked folder that may be the patch's, by their paths in it, down to a few folders deep. */
    private void walkPatchTree(Uri tree, String document, String path, int depth, Map<String, Uri> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 7) walkPatchTree(tree, id, path + name + "/", depth + 1, found);
                } else if (patchFile(name)) {
                    found.put(path + name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        }
    }

    /** imports the patch from a folder the player picked, at any level of the Luma layout. */
    private void importPatchFolder(Uri tree) {
        setBusy(true, "Buscando el parche en la carpeta…");
        new Thread(() -> {
            File staging = new File(Paths.mod(this), "patch.new");
            try {
                Map<String, Uri> found = new LinkedHashMap<>();
                walkPatchTree(tree, DocumentsContract.getTreeDocumentId(tree), "", 0, found);
                String[] chosen = choosePatchFiles(found.keySet());
                if (chosen == null) throw new IOException(noPatchMessage(found.keySet()));
                Paths.deleteTree(staging);
                staging.mkdirs();
                long total = 0, done = 0;
                for (String path : chosen) if (path != null) total += Math.max(0, size(found.get(path)));
                File[] staged = new File[chosen.length];
                for (int i = 0; i < chosen.length; i++) {
                    if (chosen[i] == null) continue;
                    staged[i] = new File(staging, i + ".part");
                    try (InputStream in = getContentResolver().openInputStream(found.get(chosen[i]))) {
                        if (in == null) throw new IOException("no se pudo abrir " + chosen[i]);
                        done = copyShown(in, staged[i], done, total, "Copiando el parche");
                    }
                }
                installPatch(staged, chosen);
            } catch (Exception e) {
                error("No se pudo instalar el parche: " + e.getMessage());
            } finally {
                Paths.deleteTree(staging);
                setBusy(false, "");
            }
        }).start();
    }

    /** imports the patch from its .zip, with the Luma layout at any depth inside it. */
    private void importPatchZip(Uri uri) {
        long total = size(uri);
        setBusy(true, "Leyendo el .zip del parche…");
        new Thread(() -> {
            File staging = new File(Paths.mod(this), "patch.new");
            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("no se pudo abrir el archivo");
                Paths.deleteTree(staging);
                staging.mkdirs();
                // a zip streams front to back once: every file that may be the patch's comes out, then the right ones
                // are chosen. Names without the UTF-8 flag are read as Latin-1, which never fails and keeps ASCII as is
                CountingStream counted = new CountingStream(new BufferedInputStream(raw, 1 << 20));
                ZipInputStream zip = new ZipInputStream(counted, java.nio.charset.StandardCharsets.ISO_8859_1);
                Map<String, File> found = new LinkedHashMap<>();
                byte[] buffer = new byte[1 << 20];
                long lastShown = 0;
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String path = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || !patchFile(baseName(path))) continue;
                    File out = new File(staging, found.size() + ".part");
                    try (OutputStream file = new FileOutputStream(out)) {
                        int read;
                        while ((read = zip.read(buffer)) > 0) {
                            file.write(buffer, 0, read);
                            if (counted.count - lastShown > (8 << 20)) {
                                lastShown = counted.count;
                                showProgress(counted.count, total, "Leyendo el .zip del parche");
                            }
                        }
                    }
                    found.put(path, out);
                }
                String[] chosen = choosePatchFiles(found.keySet());
                if (chosen == null) throw new IOException(noPatchMessage(found.keySet()));
                File[] staged = new File[chosen.length];
                for (int i = 0; i < chosen.length; i++) if (chosen[i] != null) staged[i] = found.get(chosen[i]);
                installPatch(staged, chosen);
            } catch (Exception e) {
                error("No se pudo instalar el parche: " + e.getMessage());
            } finally {
                Paths.deleteTree(staging);
                setBusy(false, "");
            }
        }).start();
    }

    /** copies in to out, showing the bytes done of total, and returns done with what it copied. */
    private long copyShown(InputStream in, File out, long done, long total, String what) throws IOException {
        byte[] buffer = new byte[1 << 20];
        long lastShown = done;
        try (OutputStream file = new FileOutputStream(out)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                file.write(buffer, 0, read);
                done += read;
                if (done - lastShown > (8 << 20)) {
                    lastShown = done;
                    showProgress(done, total, what);
                }
            }
        }
        return done;
    }

    /** the patch's files, and the stamp of the one 0.4.1 shipped, out of mods/<title>; textures stay. */
    private void removePatchFiles() {
        Paths.patchCode(this).delete();
        Paths.patchExheader(this).delete();
        File cpk = Paths.patchCpk(this);
        cpk.delete();
        // the romfs folder, only when nothing else is in it
        cpk.getParentFile().delete();
        new File(Paths.mod(this), ".patch-version").delete();
    }

    /**
     * puts the staged code.bin, exheader.bin and cpk in place of the patch there was, as Zakuro reads them
     * (romfs/mod.cpk), drops the game recompiled with the old code, and tells what was found.
     */
    private void installPatch(File[] staged, String[] from) throws IOException {
        removePatchFiles();
        File[] targets = {Paths.patchCode(this), Paths.patchExheader(this), Paths.patchCpk(this)};
        for (int i = 0; i < targets.length; i++) {
            if (staged[i] == null) continue;
            targets[i].getParentFile().mkdirs();
            if (!staged[i].renameTo(targets[i])) throw new IOException("no se pudo escribir " + targets[i].getName());
        }
        Paths.forgetRecompiled(this);
        Log.i("PQ2", "patch installed from " + String.join(", ", String.valueOf(from[0]), String.valueOf(from[1]), String.valueOf(from[2])));
        String folder = from[0].substring(0, from[0].length() - "code.bin".length());
        StringBuilder report = new StringBuilder("Encontrado en ").append(folder.isEmpty() ? "la carpeta elegida" : folder).append(":\n\n");
        report.append("✔ code.bin\n");
        report.append(from[1] != null ? "✔ exheader.bin\n" : "– exheader.bin: no está, se usa el del juego\n");
        report.append(from[2] != null
            ? String.format(Locale.US, "✔ %s → romfs/mod.cpk (%.1f MB)\n", from[2].substring(folder.length()), targets[2].length() / 1e6)
            : "⚠ Falta romfs/Patch.cpk: sin él los textos del juego no cambian.\n");
        report.append("\nLa próxima vez que juegues, el juego se vuelve a traducir para el móvil con el parche (alrededor de una hora y cuarto en una tableta de gama media).");
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Parche en español instalado").setMessage(report.toString())
            .setPositiveButton("OK", null).show());
    }

    private void play() {
        if (Paths.game(this) == null) return;
        // the C compiler's library, unpacked once before the first start: the game is recompiled on the phone
        if (!Compiler.unpacked(this)) {
            String what = "Preparando el compilador (solo la primera vez)";
            setBusy(true, what + "…");
            new Thread(() -> {
                try {
                    Compiler.unpack(this, (done, total) -> {
                        if (total > 0) showProgress(done, total, what);
                    });
                } catch (Exception e) {
                    Log.w("PQ2", "could not unpack the compiler", e);
                    runOnUiThread(() -> Toast.makeText(this, "No se pudo preparar el compilador (" + e.getMessage()
                        + "): el juego correrá sin recompilar, mucho más lento.", Toast.LENGTH_LONG).show());
                }
                setBusy(false, "");
                runOnUiThread(this::launch);
            }).start();
            return;
        }
        launch();
    }

    private void launch() {
        File game = Paths.game(this);
        if (game == null) return;
        File data = Paths.data(this);
        data.mkdirs();
        // también en un archivo, que el código nativo lee si no ve el entorno
        try (OutputStream out = new FileOutputStream(new File(getFilesDir(), "launch.txt"))) {
            out.write((game.getAbsolutePath() + "\n" + data.getAbsolutePath() + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            error("No se pudo preparar el juego: " + e.getMessage());
            return;
        }
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra("rom", game.getAbsolutePath());
        intent.putExtra("data", data.getAbsolutePath());
        startActivity(intent);
    }

    /** Un InputStream que cuenta los bytes leídos, para la barra de progreso. */
    static final class CountingStream extends java.io.FilterInputStream {
        volatile long count;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }
    }

    private void exportLogs() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "pq2-log.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== Persona Q2 Port Log Export ===\n");
            try {
                sb.append("App: ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append("\n");
            } catch (Exception ignored) {}
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("SoC: ").append(Compat.socVendor()).append(" (").append(Compat.soc()).append(")\n");
            sb.append("Date: ").append(new java.util.Date().toString()).append("\n\n");

            try {
                Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-t", "500"});
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("logcat capture error: ").append(e.getMessage()).append("\n");
            }

            File[] possibleLogs = new File[] {
                new File(Paths.data(this), "zakuro.log"),
                new File(Paths.data(this), "gpu.txt"),
                new File(getFilesDir(), "zakuro.log"),
                new File(getFilesDir(), "gpu.txt"),
                new File(Paths.base(this), "zakuro.log")
            };
            for (File zLog : possibleLogs) {
                if (zLog != null && zLog.exists() && zLog.canRead()) {
                    sb.append("\n=== ").append(zLog.getName()).append(" (").append(zLog.getPath()).append(") ===\n");
                    try (BufferedReader br = new BufferedReader(new FileReader(zLog))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    }
                }
            }

            try (FileWriter fw = new FileWriter(out)) {
                fw.write(sb.toString());
            }

            Toast.makeText(this, "Registros exportados a Download/pq2-log.txt", Toast.LENGTH_SHORT).show();

            Uri logUri = Uri.parse("content://" + getPackageName() + ".logprovider" + out.getAbsolutePath());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, logUri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Persona Q2 Debug Log");
            share.setClipData(android.content.ClipData.newRawUri("Log", logUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir archivo de registros"));
        } catch (Exception e) {
            Toast.makeText(this, "Error al exportar registros: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}
