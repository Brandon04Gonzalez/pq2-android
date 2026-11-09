package com.pq2port.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Los controles del 3DS dibujados sobre el juego, y la pantalla táctil de
 * abajo: cada dedo queda con lo que tocó al bajar, y lo que sostienen todos
 * se manda a Zakuro.
 */
final class ControlsView extends View {
    // los bits de los botones como los da el hardware (PadState de Zakuro)
    static final int A = 1, B = 1 << 1, SELECT = 1 << 2, START = 1 << 3, RIGHT = 1 << 4, LEFT = 1 << 5,
        UP = 1 << 6, DOWN = 1 << 7, R = 1 << 8, L = 1 << 9, X = 1 << 10, Y = 1 << 11;

    private static final int NONE = 0, BUTTON = 1, DPAD = 2, STICK = 3, SCREEN = 4, MENU = 5;
    private static final int MAX_POINTERS = 10;

    /** un botón redondo o rectangular */
    private static final class Key {
        final int bit;
        final String label;
        final RectF rect = new RectF();
        boolean round;
        boolean held;

        Key(int bit, String label, boolean round) {
            this.bit = bit;
            this.label = label;
            this.round = round;
        }

        boolean hit(float x, float y, float slop) {
            if (round) {
                float cx = rect.centerX(), cy = rect.centerY(), r = rect.width() / 2 + slop;
                return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r;
            }
            return x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop;
        }
    }

    private final Key[] faceKeys = {new Key(A, "A", true), new Key(B, "B", true), new Key(X, "X", true), new Key(Y, "Y", true)};
    private final Key[] otherKeys = {new Key(L, "L", false), new Key(R, "R", false), new Key(SELECT, "SELECT", false), new Key(START, "START", false)};
    private final RectF menuRect = new RectF();
    private float dpadX, dpadY, dpadR, stickX, stickY, stickR;
    private final RectF bottomScreen = new RectF();
    private final RectF topScreen = new RectF();

    // lo que tiene cada dedo
    private final int[] kind = new int[MAX_POINTERS];
    private final int[] keyBits = new int[MAX_POINTERS];
    private int dpadBits;
    private float knobX, knobY;
    private int touchPointer = -1;
    private long menuDownAt;

    private int sentButtons = -1;
    private float sentX = 2, sentY = 2;
    private boolean hidden;
    private boolean running;
    private float fps;
    /** a cuántas veces la resolución del 3DS se dibuja el 3D, 0 en software. */
    private int scale;
    // los ajustes del lanzador
    private final boolean showFps;
    private final float sizeFactor;
    private final float opacity;
    private long lastTop = -1, lastBottom = -1;
    private int lastDx, lastDy;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;

    ControlsView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE);
        hidden = prefs.getBoolean("hidden", false);
        showFps = Options.showFps(context);
        sizeFactor = Options.controlsSize(context);
        opacity = Options.controlsOpacity(context);
        stroke.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        setHapticFeedbackEnabled(true);
    }

    /** dónde están las pantallas del juego, en píxeles de la ventana del juego, y su desplazamiento respecto a esta */
    void setScreens(long top, long bottom, int dx, int dy, boolean running, float fps, int scale) {
        boolean changed = top != lastTop || bottom != lastBottom || dx != lastDx || dy != lastDy || running != this.running;
        this.running = running;
        if (Math.abs(fps - this.fps) >= 0.5f || scale != this.scale) {
            this.fps = fps;
            this.scale = scale;
            invalidate();
        }
        if (!changed) return;
        lastTop = top;
        lastBottom = bottom;
        lastDx = dx;
        lastDy = dy;
        unpack(top, topScreen, dx, dy);
        unpack(bottom, bottomScreen, dx, dy);
        layoutControls();
        invalidate();
    }

    private static void unpack(long packed, RectF rect, int dx, int dy) {
        if (packed == 0) {
            rect.setEmpty();
            return;
        }
        float x = (packed >>> 48) & 0xFFFF, y = (packed >>> 32) & 0xFFFF, w = (packed >>> 16) & 0xFFFF, h = packed & 0xFFFF;
        rect.set(x + dx, y + dy, x + dx + w, y + dy + h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutControls();
    }

    private void layoutControls() {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean portrait = h > w;
        float u;
        if (portrait) {
            float freeTop = bottomScreen.isEmpty() ? h * 0.55f : bottomScreen.bottom;
            float freeH = Math.max(h - freeTop, h * 0.3f);
            u = Math.min(w / 8.5f, freeH / 6.4f) * sizeFactor;
            float row = freeTop + 0.25f * u;
            otherKeys[0].rect.set(0.25f * u, row, 2.6f * u, row + 0.95f * u);
            otherKeys[1].rect.set(w - 2.6f * u, row, w - 0.25f * u, row + 0.95f * u);
            menuRect.set(w / 2 - 0.55f * u, row, w / 2 + 0.55f * u, row + 1.1f * u);
            float middle = row + 0.95f * u + (h - row - 0.95f * u - 1.2f * u) / 2;
            stickX = 1.75f * u;
            stickY = middle - 1.35f * u;
            stickR = 1.2f * u;
            dpadX = 1.75f * u;
            dpadY = middle + 1.35f * u;
            dpadR = 1.25f * u;
            placeFace(w - 2.15f * u, middle, u);
            float bottomRow = h - 0.95f * u;
            otherKeys[2].rect.set(w / 2 - 2.3f * u, bottomRow, w / 2 - 0.4f * u, bottomRow + 0.65f * u);
            otherKeys[3].rect.set(w / 2 + 0.4f * u, bottomRow, w / 2 + 2.3f * u, bottomRow + 0.65f * u);
        } else {
            u = Math.min(w / 17f, h / 7.2f) * sizeFactor;
            otherKeys[0].rect.set(0.2f * u, 0.2f * u, 2.6f * u, 1.1f * u);
            otherKeys[1].rect.set(w - 2.6f * u, 0.2f * u, w - 0.2f * u, 1.1f * u);
            menuRect.set(w / 2 - 0.5f * u, 0.15f * u, w / 2 + 0.5f * u, 1.15f * u);
            stickX = 1.7f * u;
            stickR = 1.15f * u;
            dpadX = 1.7f * u;
            dpadY = h - 1.6f * u;
            dpadR = 1.2f * u;
            // el stick entre L y la cruceta, sin pisarla con los controles grandes
            stickY = Math.max(1.2f * u + stickR, Math.min(h * 0.42f, dpadY - dpadR - stickR - 0.15f * u));
            // los botones encima de la pantalla de abajo cuando está a la derecha
            float faceY = bottomScreen.isEmpty() ? h * 0.45f : Math.min(h * 0.45f, bottomScreen.top - 1.5f * u);
            placeFace(w - 2.0f * u, Math.max(faceY, 2.6f * u), u);
            otherKeys[2].rect.set(3.4f * u, h - 0.85f * u, 5.2f * u, h - 0.2f * u);
            otherKeys[3].rect.set(5.5f * u, h - 0.85f * u, 7.3f * u, h - 0.2f * u);
        }
        label.setTextSize(0.42f * u);
        stroke.setStrokeWidth(Math.max(2f, 0.04f * u));
    }

    private void placeFace(float cx, float cy, float u) {
        float r = 0.6f * u, d = 1.15f * u;
        faceKeys[0].rect.set(cx + d - r, cy - r, cx + d + r, cy + r); // A, derecha
        faceKeys[1].rect.set(cx - r, cy + d - r, cx + r, cy + d + r); // B, abajo
        faceKeys[2].rect.set(cx - r, cy - d - r, cx + r, cy - d + r); // X, arriba
        faceKeys[3].rect.set(cx - d - r, cy - r, cx - d + r, cy + r); // Y, izquierda
    }

    // ---------------------------------------------------------------- toques

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(event.getPointerId(index), event.getX(index), event.getY(index));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) move(event.getPointerId(i), event.getX(i), event.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(event.getPointerId(index));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        send();
        return true;
    }

    private void down(int id, float x, float y) {
        if (id >= MAX_POINTERS) return;
        float slop = getWidth() * 0.01f;
        if (menuRect.contains(x, y) || hit(menuRect, x, y, slop)) {
            kind[id] = MENU;
            menuDownAt = System.currentTimeMillis();
            buzz();
            return;
        }
        if (!hidden) {
            for (Key key : otherKeys) {
                if (key.hit(x, y, slop)) {
                    kind[id] = BUTTON;
                    keyBits[id] = key.bit;
                    buzz();
                    return;
                }
            }
            for (Key key : faceKeys) {
                if (key.hit(x, y, slop)) {
                    kind[id] = BUTTON;
                    keyBits[id] = key.bit;
                    buzz();
                    return;
                }
            }
            if (dist(x, y, dpadX, dpadY) <= dpadR * 1.25f) {
                kind[id] = DPAD;
                dpad(x, y);
                buzz();
                return;
            }
            if (dist(x, y, stickX, stickY) <= stickR * 1.35f) {
                kind[id] = STICK;
                stick(x, y);
                return;
            }
        }
        if (bottomScreen.contains(x, y) && touchPointer < 0) {
            kind[id] = SCREEN;
            touchPointer = id;
            screen(x, y, true);
            return;
        }
        kind[id] = NONE;
    }

    private void move(int id, float x, float y) {
        if (id >= MAX_POINTERS) return;
        switch (kind[id]) {
            case BUTTON:
                // un dedo puede deslizarse de un botón A/B/X/Y a otro
                for (Key key : faceKeys) {
                    if (key.hit(x, y, 0) && key.bit != keyBits[id] && isFace(keyBits[id])) {
                        keyBits[id] = key.bit;
                        buzz();
                    }
                }
                break;
            case DPAD:
                dpad(x, y);
                break;
            case STICK:
                stick(x, y);
                break;
            case SCREEN:
                screen(x, y, true);
                break;
            default:
                break;
        }
    }

    private void up(int id) {
        if (id >= MAX_POINTERS) return;
        switch (kind[id]) {
            case MENU:
                if (System.currentTimeMillis() - menuDownAt > 600) {
                    // pulsación larga: mostrar u ocultar los controles (para jugar con mando)
                    hidden = !hidden;
                    prefs.edit().putBoolean("hidden", hidden).apply();
                    invalidate();
                } else {
                    GameActivity.nativeRequestMenu();
                }
                break;
            case DPAD:
                dpadBits = 0;
                break;
            case STICK:
                knobX = 0;
                knobY = 0;
                break;
            case SCREEN:
                if (touchPointer == id) {
                    touchPointer = -1;
                    GameActivity.nativeSetTouch((byte) 0, 0, 0);
                }
                break;
            default:
                break;
        }
        kind[id] = NONE;
        keyBits[id] = 0;
    }

    void releaseAll() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            kind[i] = NONE;
            keyBits[i] = 0;
        }
        dpadBits = 0;
        knobX = 0;
        knobY = 0;
        touchPointer = -1;
        try {
            GameActivity.nativeSetTouch((byte) 0, 0, 0);
        } catch (UnsatisfiedLinkError ignored) {
        }
        send();
    }

    private static boolean isFace(int bit) {
        return bit == A || bit == B || bit == X || bit == Y;
    }

    private static boolean hit(RectF rect, float x, float y, float slop) {
        return x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop;
    }

    private static float dist(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    private void dpad(float x, float y) {
        float dx = x - dpadX, dy = y - dpadY;
        int bits = 0;
        if (Math.hypot(dx, dy) > dpadR * 0.22f) {
            double angle = Math.toDegrees(Math.atan2(-dy, dx));
            if (angle < 0) angle += 360;
            // ocho direcciones, las diagonales con dos bits
            if (angle < 67.5 || angle >= 292.5) bits |= RIGHT;
            if (angle >= 22.5 && angle < 157.5) bits |= UP;
            if (angle >= 112.5 && angle < 247.5) bits |= LEFT;
            if (angle >= 202.5 && angle < 337.5) bits |= DOWN;
        }
        if (bits != dpadBits && bits != 0) buzz();
        dpadBits = bits;
    }

    private void stick(float x, float y) {
        float dx = (x - stickX) / stickR, dy = (y - stickY) / stickR;
        float length = (float) Math.hypot(dx, dy);
        if (length > 1) {
            dx /= length;
            dy /= length;
        }
        knobX = dx;
        knobY = dy;
    }

    private void screen(float x, float y, boolean active) {
        int sx = (int) ((x - bottomScreen.left) / bottomScreen.width() * 320f);
        int sy = (int) ((y - bottomScreen.top) / bottomScreen.height() * 240f);
        GameActivity.nativeSetTouch((byte) (active ? 1 : 0), Math.max(0, Math.min(319, sx)), Math.max(0, Math.min(239, sy)));
    }

    private void buzz() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void send() {
        int buttons = dpadBits;
        for (int i = 0; i < MAX_POINTERS; i++) if (kind[i] == BUTTON) buttons |= keyBits[i];
        try {
            if (buttons != sentButtons) {
                sentButtons = buttons;
                GameActivity.nativeSetButtons(buttons);
            }
            if (knobX != sentX || knobY != sentY) {
                sentX = knobX;
                sentY = knobY;
                // en el 3DS arriba es positivo
                GameActivity.nativeSetCirclePad(knobX, -knobY);
            }
        } catch (UnsatisfiedLinkError ignored) {
        }
        for (Key key : faceKeys) key.held = (buttons & key.bit) != 0;
        for (Key key : otherKeys) key.held = (buttons & key.bit) != 0;
        invalidate();
    }

    // ---------------------------------------------------------------- dibujo

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) {
            label.setColor(Color.WHITE);
            label.setTextSize(getWidth() / 24f);
            canvas.drawText("Cargando Persona Q2…", getWidth() / 2f, getHeight() / 2f, label);
            layoutControls();
            return;
        }
        // contador de FPS discreto, con la resolución a la que se dibuja el 3D
        if (showFps) {
            label.setColor(0x99FFFFFF);
            float small = menuRect.height() * 0.32f;
            label.setTextSize(small);
            String text = scale > 0 ? String.format(Locale.US, "%.0f FPS · %dx", fps, scale) : String.format(Locale.US, "%.0f FPS", fps);
            canvas.drawText(text, menuRect.centerX(), menuRect.bottom + small * 1.3f, label);
        }
        layoutControls();
        // el botón del menú siempre se ve, aunque los controles sean casi transparentes
        float kept = Math.max(opacity, 0.6f);
        drawButton(canvas, menuRect, "≡", true, false, kept);
        if (hidden) return;
        for (Key key : otherKeys) drawButton(canvas, key.rect, key.label, key.round, key.held, opacity);
        for (Key key : faceKeys) drawButton(canvas, key.rect, key.label, true, key.held, opacity);
        drawDpad(canvas);
        drawStick(canvas);
    }

    /** el color con su transparencia multiplicada por la opacidad elegida. */
    private static int faded(int color, float opacity) {
        int alpha = Math.round((color >>> 24) * opacity);
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private int faded(int color) {
        return faded(color, opacity);
    }

    private void drawButton(Canvas canvas, RectF rect, String text, boolean round, boolean held, float opacity) {
        fill.setColor(faded(held ? 0x99E8261C : 0x33FFFFFF, opacity));
        stroke.setColor(faded(0x88FFFFFF, opacity));
        if (round) {
            float r = Math.min(rect.width(), rect.height()) / 2;
            canvas.drawCircle(rect.centerX(), rect.centerY(), r, fill);
            canvas.drawCircle(rect.centerX(), rect.centerY(), r, stroke);
        } else {
            float r = rect.height() / 2;
            canvas.drawRoundRect(rect, r, r, fill);
            canvas.drawRoundRect(rect, r, r, stroke);
        }
        label.setColor(faded(0xDDFFFFFF, opacity));
        float size = round ? rect.height() * 0.42f : rect.height() * (text.length() > 2 ? 0.38f : 0.5f);
        label.setTextSize(size);
        canvas.drawText(text, rect.centerX(), rect.centerY() + size * 0.36f, label);
    }

    private void drawDpad(Canvas canvas) {
        float arm = dpadR * 0.36f;
        stroke.setColor(faded(0x88FFFFFF));
        RectF horizontal = new RectF(dpadX - dpadR, dpadY - arm, dpadX + dpadR, dpadY + arm);
        RectF vertical = new RectF(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY + dpadR);
        fill.setColor(faded(0x33FFFFFF));
        canvas.drawRoundRect(horizontal, arm * 0.4f, arm * 0.4f, fill);
        canvas.drawRoundRect(vertical, arm * 0.4f, arm * 0.4f, fill);
        canvas.drawRoundRect(horizontal, arm * 0.4f, arm * 0.4f, stroke);
        canvas.drawRoundRect(vertical, arm * 0.4f, arm * 0.4f, stroke);
        fill.setColor(faded(0x99E8261C));
        float reach = dpadR * 0.62f;
        if ((dpadBits & UP) != 0) canvas.drawCircle(dpadX, dpadY - reach, arm * 0.8f, fill);
        if ((dpadBits & DOWN) != 0) canvas.drawCircle(dpadX, dpadY + reach, arm * 0.8f, fill);
        if ((dpadBits & LEFT) != 0) canvas.drawCircle(dpadX - reach, dpadY, arm * 0.8f, fill);
        if ((dpadBits & RIGHT) != 0) canvas.drawCircle(dpadX + reach, dpadY, arm * 0.8f, fill);
    }

    private void drawStick(Canvas canvas) {
        fill.setColor(faded(0x22FFFFFF));
        stroke.setColor(faded(0x66FFFFFF));
        canvas.drawCircle(stickX, stickY, stickR, fill);
        canvas.drawCircle(stickX, stickY, stickR, stroke);
        boolean active = knobX != 0 || knobY != 0;
        fill.setColor(faded(active ? 0x99E8261C : 0x55FFFFFF));
        canvas.drawCircle(stickX + knobX * stickR * 0.6f, stickY + knobY * stickR * 0.6f, stickR * 0.45f, fill);
    }
}
