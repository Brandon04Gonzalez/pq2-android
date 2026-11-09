package com.pq2port.app;

import android.app.NativeActivity;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * The game: the NativeActivity whose library (libmain.so) is Zakuro. The game's
 * code is not in the APK: Zakuro recompiles it on the phone the first time and
 * then loads files/3dsrecomp/<title>.so. The touch controls go in a window of
 * their own on top, as the NativeActivity takes every touch in its own.
 */
public class GameActivity extends NativeActivity {
    private static final String TAG = "PQ2";

    // la NativeActivity abre la librería por su cuenta, y Java solo encuentra
    // los métodos nativos de las que cargó él
    static {
        System.loadLibrary("main");
    }

    static native void nativeSetButtons(int mask);
    static native void nativeSetCirclePad(float x, float y);
    static native void nativeSetTouch(byte active, int x, int y);
    static native void nativeRequestMenu();
    static native byte nativeMenuOpen();
    static native byte nativeRunning();
    static native long nativeTopRect();
    static native long nativeBottomRect();
    static native float nativeFps();
    static native int nativeScale();

    private ControlsView controls;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayShown;
    private boolean menuOpen;
    /** cuándo arrancó, y si ya corrió lo bastante como para no avisar de un cierre. */
    private long startedAt;
    private boolean settled;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // lo que Zakuro lee del entorno, antes de que la librería arranque
        String rom = getIntent().getStringExtra("rom");
        String data = getIntent().getStringExtra("data");
        try {
            Os.setenv("ZAKURO_ROM", rom == null ? "" : rom, true);
            Os.setenv("ZAKURO_DATA_DIR", data == null ? getFilesDir().getPath() : data, true);
            Os.setenv("HOME", data == null ? getFilesDir().getPath() : data, true);
            Os.setenv("XDG_CONFIG_HOME", (data == null ? getFilesDir().getPath() : data) + "/config", true);
            // where 3dsrecomp installs the game recompiled on the phone, and Zakuro loads it from:
            // internal storage, as external storage is mounted noexec
            Os.setenv("XDG_DATA_HOME", getFilesDir().getPath(), true);
            Os.setenv("TMPDIR", getCacheDir().getPath(), true);
            // el código recompilado usa la pila nativa para las llamadas del juego
            Os.setenv("RUST_MIN_STACK", String.valueOf(64 << 20), true);
            Os.setenv("RUST_LOG", "info,cranelift_jit=warn,cranelift_codegen=warn,naga=warn", true);
            Os.setenv("RUST_BACKTRACE", "1", true);
            // qué GPU dibuja el 3D y cómo, para que el lanzador lo muestre
            Os.setenv("ZAKURO_GPU_REPORT", (data == null ? getFilesDir().getPath() : data) + "/gpu.txt", true);
            // the C compiler the game is recompiled with on the phone the first time; env.txt below can override it
            Compiler.apply(this);
            // lo que pide el modo de compatibilidad de la GPU, y lo que se
            // quiera probar a mano en zakuro/env.txt (CLAVE=valor por línea)
            for (String[] pair : Compat.environment(this)) {
                Os.setenv(pair[0], pair[1], true);
            }
        } catch (Exception e) {
            Log.e(TAG, "setenv", e);
        }
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        // si el proceso muere antes de que el juego corra un rato, el
        // lanzador lo sabrá y ofrecerá el modo de compatibilidad
        Compat.markStarting(this);
        startedAt = android.os.SystemClock.elapsedRealtime();
        super.onCreate(savedInstanceState);
        immersive();
        controls = new ControlsView(this);
        window.getDecorView().post(this::showOverlay);
        handler.post(poll);
    }

    private void immersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController insets = decor.getWindowInsetsController();
            if (insets != null) {
                insets.hide(WindowInsets.Type.systemBars());
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void showOverlay() {
        if (overlayShown || isFinishing()) return;
        View decor = getWindow().getDecorView();
        if (decor.getWindowToken() == null) {
            decor.post(this::showOverlay);
            return;
        }
        overlayParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT);
        overlayParams.token = decor.getWindowToken();
        if (Build.VERSION.SDK_INT >= 28) {
            overlayParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            getWindowManager().addView(controls, overlayParams);
            overlayShown = true;
        } catch (Exception e) {
            Log.e(TAG, "overlay", e);
        }
    }

    /** sigue al juego: el menú abierto deja los toques a Zakuro, y las pantallas mandan dónde van los controles. */
    private void update() {
        if (!overlayShown) return;
        boolean running;
        boolean menu;
        try {
            running = nativeRunning() != 0;
            menu = nativeMenuOpen() != 0;
        } catch (UnsatisfiedLinkError e) {
            return;
        }
        if (menu != menuOpen) {
            menuOpen = menu;
            controls.releaseAll();
            controls.setVisibility(menu ? View.GONE : View.VISIBLE);
            if (menu) overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                getWindowManager().updateViewLayout(controls, overlayParams);
            } catch (Exception ignored) {
            }
        }
        if (!settled && running && android.os.SystemClock.elapsedRealtime() - startedAt > 20_000) {
            settled = true;
            Compat.markRunning(this);
        }
        int[] game = new int[2];
        int[] overlay = new int[2];
        getWindow().getDecorView().getLocationOnScreen(game);
        controls.getLocationOnScreen(overlay);
        controls.setScreens(nativeTopRect(), nativeBottomRect(), game[0] - overlay[0], game[1] - overlay[1], running, nativeFps(), nativeScale());
    }

    @Override
    public void onBackPressed() {
        // Atrás abre el menú del juego en lugar de cerrarlo
        try {
            nativeRequestMenu();
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    protected void onPause() {
        if (controls != null) controls.releaseAll();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (settled) Compat.markRunning(this);
        else Compat.markStarting(this);
    }

    @Override
    protected void onStop() {
        // en segundo plano Android puede cerrar el proceso sin que sea un error
        Compat.clearMark(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (overlayShown) {
            try {
                getWindowManager().removeViewImmediate(controls);
            } catch (Exception ignored) {
            }
            overlayShown = false;
        }
        if (isFinishing()) Compat.clearMark(this);
        super.onDestroy();
        // el proceso del juego termina con él, la próxima partida empieza limpia
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }
}
