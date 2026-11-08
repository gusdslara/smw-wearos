package net.freyta.smw.wear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.util.HashMap;
import java.util.Map;

/**
 * Overlay de controles inteligentes para relógio (Wear OS) adaptado para Super Mario World:
 *
 * 1. Deslize (Swipe) com 1 dedo:
 *    - Toque rápido / flick = 1 toque direcional (Up / Down / Left / Right).
 *    - Manter o dedo na tela = Continua andando na direção desejada (analógico com âncora seguidora).
 *
 * 2. Deslize com 2 dedos (Two-Finger Swipe):
 *    - Arraste em qualquer direção com 2 dedos = MARIO RUN (segura KEY_Y / Correr enquanto direciona!).
 *
 * 3. Zonas de Toque Inteligentes (Round Touch Zones):
 *    - Topo Esquerdo: L
 *    - Topo Centro:   SELECT
 *    - Topo Direito:  R
 *    - Meio Esquerdo: B (Pulo normal)
 *    - Meio Direito:  A (Spin Jump)
 *    - Fundo Centro:  START (Pausa)
 *
 * 4. Segundo dedo durante a caminhada (Multitouch):
 *    - Enquanto o primeiro dedo anda, o segundo dedo encostando na tela segura o botão daquela zona
 *      (ex: segurar B para pular ou correr sem parar de andar).
 *
 * 5. High Jump por Gesto de Braço / Sacudida:
 *    - Acionado externamente pelo sensor do relógio (WearSMWActivity).
 *
 * 6. Feedback Visual & Tátil:
 *    - Arcos luminosos coloridos na borda circular identificando cada ação.
 */
public class WearSmartTouchOverlay extends View {

    // Toggle para prints de divulgação (true = tela escurecida e controles 100% visíveis; false = jogo limpo com toque reativo)
    public static boolean SHOW_CONTROLS_FOR_PRINT = false;


    // Teclas mapeadas no smw.ini:
    public static final int KEY_UP = KeyEvent.KEYCODE_Y;
    public static final int KEY_DOWN = KeyEvent.KEYCODE_H;
    public static final int KEY_LEFT = KeyEvent.KEYCODE_G;
    public static final int KEY_RIGHT = KeyEvent.KEYCODE_J;
    public static final int KEY_START = KeyEvent.KEYCODE_N;
    public static final int KEY_SELECT = KeyEvent.KEYCODE_V;
    public static final int KEY_A = KeyEvent.KEYCODE_X;  // Spin Jump
    public static final int KEY_B = KeyEvent.KEYCODE_Z;  // Normal Jump / Confirm
    public static final int KEY_X = KeyEvent.KEYCODE_S;
    public static final int KEY_Y = KeyEvent.KEYCODE_A;  // Run / Dash / Fireball
    public static final int KEY_L = KeyEvent.KEYCODE_C;
    public static final int KEY_R = KeyEvent.KEYCODE_BACK;

    private static final float CONFIG_SWIPE = 0.12f;
    private static final float CONFIG_JOYSTICK_MAX = 0.25f;
    private static final float CONFIG_TAP_BAND = 0.42f;
    private static final float CONFIG_SHOULDER_X = 0.30f;
    private static final long CONFIG_TAP_HOLD_MS = 90;
    private static final long SHORT_HOP_HOLD_MS = 60;
    private static final long FLASH_DURATION_MS = 280;

    /** Tempo do último término de toque (ms) para proteger contra bezel/coroa acidental ao andar */
    private long lastTouchEndTime = 0;

    /** Retorna true se houver qualquer dedo na tela ou se um toque terminou recentemente */
    public boolean isTouchGuarded(long guardDurationMs) {
        if (!touches.isEmpty()) {
            return true;
        }
        return (System.currentTimeMillis() - lastTouchEndTime) < guardDurationMs;
    }

    private static class TouchRecord {
        float x0, y0;
        float x, y;
        long t0;
        String kind; // "pending", "dir", "aux", "two", "ignored"
        String btn;
        int key;
    }

    private static class TwoFingerRecord {
        float cx0, cy0;
        float cx, cy;
        long t0;
        boolean isRunning;
        int dirKey;
    }

    private static class FlashArc {
        String label;
        float angle;
        int color;
        long startTime;
    }

    private final Map<Integer, TouchRecord> touches = new HashMap<>();
    private Integer primaryTouchId = null;
    private TwoFingerRecord twoFinger = null;

    private int currentDirKey = 0;
    private long dirSince = 0;
    private boolean isArmJumpHolding = false;

    private final Map<String, FlashArc> activeFlashes = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Vibrator vibrator;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ovalRect = new RectF();
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF labelRect = new RectF();
    private RadialGradient vignetteShader = null;
    private float lastVignetteR = -1f;

    public WearSmartTouchOverlay(Context context) {
        super(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(21f);
        textPaint.setShadowLayer(5f, 0, 1.5f, Color.argb(220, 0, 0, 0));

        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeCap(Paint.Cap.ROUND);

        labelBgPaint.setStyle(Paint.Style.FILL);
        labelBgPaint.setColor(Color.argb(175, 12, 14, 18));

        setWillNotDraw(false);
        setElevation(100f);
    }

    public void buzz(long millis) {
        if (vibrator != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(millis);
                }
            } catch (Exception ignored) {}
        }
    }

    public void flashButton(String btn) {
        FlashArc arc = new FlashArc();
        arc.label = btn;
        arc.startTime = System.currentTimeMillis();

        switch (btn) {
            case "B":
                arc.angle = (float) Math.PI;          // Esquerda
                arc.color = Color.rgb(255, 115, 102); // Vermelho
                break;
            case "A":
                arc.angle = 0;                        // Direita
                arc.color = Color.rgb(102, 242, 140); // Verde
                break;
            case "RUN":
                arc.angle = (float) Math.PI;
                arc.color = Color.rgb(255, 180, 0);   // Laranja
                break;
            case "HOP":
                arc.angle = (float) Math.PI;          // Esquerda (B)
                arc.color = Color.rgb(255, 200, 100); // Âmbar claro
                break;
            case "JUMP!":
                arc.angle = (float) (-Math.PI / 2);
                arc.color = Color.rgb(0, 255, 255);   // Ciano
                break;
            case "START":
                arc.angle = (float) (Math.PI / 2);
                arc.color = Color.rgb(115, 184, 255); // Azul
                break;
            case "SELECT":
                arc.angle = (float) (-Math.PI / 2);
                arc.color = Color.rgb(217, 158, 255); // Violeta
                break;
            case "L":
                arc.angle = (float) (-3 * Math.PI / 4);
                arc.color = Color.rgb(255, 217, 89);
                break;
            case "R":
                arc.angle = (float) (-Math.PI / 4);
                arc.color = Color.rgb(255, 217, 89);
                break;
            default:
                return;
        }

        activeFlashes.put(btn, arc);
        postInvalidate();
    }

    /**
     * Dispara o PULO CURTO (Short Hop) acionado por um leve tilt do relógio na direção do jogador.
     * Dá apenas um leve tick/tap rápido no botão B (60ms).
     */
    public void triggerShortHop() {
        if (isArmJumpHolding) return;
        isArmJumpHolding = true;

        buzz(18); // Leve tick tátil
        flashButton("HOP");
        pressKey(KEY_B);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                releaseKey(KEY_B);
                isArmJumpHolding = false;
            }
        }, SHORT_HOP_HOLD_MS);
    }

    /**
     * Dispara o PULO MÁXIMO (High Jump) acionado pelo movimento rápido do braço.
     * Segura o botão B por 340ms (altura máxima da física do Super Mario World).
     */
    public void triggerArmHighJump() {
        if (isArmJumpHolding) return;
        isArmJumpHolding = true;

        buzz(35);
        flashButton("JUMP!");
        pressKey(KEY_B);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                releaseKey(KEY_B);
                isArmJumpHolding = false;
            }
        }, 340);
    }

    private String getButtonAt(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float R = Math.min(cx, cy);

        float band = CONFIG_TAP_BAND * R;
        if (y <= cy - band) {
            float side = CONFIG_SHOULDER_X * R;
            if (x <= cx - side) return "L";
            if (x >= cx + side) return "R";
            return "SELECT";
        }
        if (y >= cy + band) {
            return "START";
        }
        // B na esquerda (Normal Jump / Confirm), A na direita (Spin Jump)
        return (x >= cx) ? "A" : "B";
    }

    private int getKeyCode(String btn) {
        switch (btn) {
            case "A": return KEY_A;
            case "B": return KEY_B;
            case "START": return KEY_START;
            case "SELECT": return KEY_SELECT;
            case "L": return KEY_L;
            case "R": return KEY_R;
            default: return 0;
        }
    }

    private void pressKey(int key) {
        if (key != 0) {
            SDLActivity.onNativeKeyDown(key);
        }
    }

    private void releaseKey(int key) {
        if (key != 0) {
            SDLActivity.onNativeKeyUp(key);
        }
    }

    private void tapKey(final int key, String label) {
        if (key == 0) return;
        pressKey(key);
        flashButton(label);
        if ("START".equals(label) || "SELECT".equals(label) || "L".equals(label) || "R".equals(label)) {
            buzz(20);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                releaseKey(key);
            }
        }, CONFIG_TAP_HOLD_MS);
    }

    private int getDirectionKey(float dx, float dy, int curKey) {
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        float bias = 1.25f;

        if ((curKey == KEY_LEFT || curKey == KEY_RIGHT) && ax * bias >= ay) {
            return dx < 0 ? KEY_LEFT : KEY_RIGHT;
        } else if ((curKey == KEY_UP || curKey == KEY_DOWN) && ay * bias >= ax) {
            return dy < 0 ? KEY_UP : KEY_DOWN;
        }

        if (ax >= ay) {
            return dx < 0 ? KEY_LEFT : KEY_RIGHT;
        }
        return dy < 0 ? KEY_UP : KEY_DOWN;
    }

    private void setDirection(int newKey) {
        if (newKey == currentDirKey) return;
        if (currentDirKey != 0) {
            releaseKey(currentDirKey);
        }
        currentDirKey = newKey;
        if (currentDirKey != 0) {
            pressKey(currentDirKey);
            dirSince = System.currentTimeMillis();
        }
        postInvalidate();
    }

    private void endDirection() {
        if (currentDirKey != 0) {
            final int keyToRelease = currentDirKey;
            long heldTime = System.currentTimeMillis() - dirSince;
            if (heldTime < 140) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        releaseKey(keyToRelease);
                    }
                }, 140 - heldTime);
            } else {
                releaseKey(keyToRelease);
            }
            currentDirKey = 0;
            postInvalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        long now = System.currentTimeMillis();

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float R = Math.min(cx, cy);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = event.getX(actionIndex);
                float y = event.getY(actionIndex);

                TouchRecord p = (primaryTouchId != null) ? touches.get(primaryTouchId) : null;

                if (p == null) {
                    TouchRecord tch = new TouchRecord();
                    tch.x0 = x; tch.y0 = y;
                    tch.x = x; tch.y = y;
                    tch.t0 = now;
                    tch.kind = "pending";
                    touches.put(pointerId, tch);
                    primaryTouchId = pointerId;
                } else if ("pending".equals(p.kind) && twoFinger == null && (now - p.t0 < 350)) {
                    // Dois dedos juntos rapidamente -> Inicia gesto de dois dedos
                    p.kind = "two";
                    TouchRecord tch = new TouchRecord();
                    tch.x0 = x; tch.y0 = y;
                    tch.x = x; tch.y = y;
                    tch.t0 = now;
                    tch.kind = "two";
                    touches.put(pointerId, tch);

                    twoFinger = new TwoFingerRecord();
                    twoFinger.cx0 = (p.x + x) / 2f;
                    twoFinger.cy0 = (p.y + y) / 2f;
                    twoFinger.cx = twoFinger.cx0;
                    twoFinger.cy = twoFinger.cy0;
                    twoFinger.t0 = now;
                    twoFinger.isRunning = false;
                    twoFinger.dirKey = 0;
                } else if (p.kind != null && !"two".equals(p.kind) && !"ignored".equals(p.kind)) {
                    // Quando o dedo principal já estiver ativo (andando ou aguardando),
                    // ignoramos toques adicionais para evitar disparos acidentais de botões (ex: B ou A)
                    // causados por rolagem do polegar, palma ou bezel na tela pequena do relógio.
                    TouchRecord tch = new TouchRecord();
                    tch.kind = "ignored";
                    touches.put(pointerId, tch);
                } else {
                    TouchRecord tch = new TouchRecord();
                    tch.kind = "ignored";
                    touches.put(pointerId, tch);
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pId = event.getPointerId(i);
                    TouchRecord tch = touches.get(pId);
                    if (tch == null || "ignored".equals(tch.kind) || "aux".equals(tch.kind)) continue;

                    float x = event.getX(i);
                    float y = event.getY(i);
                    tch.x = x;
                    tch.y = y;

                    // 1. Processamento de DOIS DEDOS (Two Finger Run & Steer)
                    if ("two".equals(tch.kind) && twoFinger != null) {
                        float sumX = 0, sumY = 0;
                        int count = 0;
                        for (TouchRecord o : touches.values()) {
                            if ("two".equals(o.kind)) {
                                sumX += o.x; sumY += o.y; count++;
                            }
                        }
                        if (count > 0) {
                            twoFinger.cx = sumX / count;
                            twoFinger.cy = sumY / count;

                            float dx = twoFinger.cx - twoFinger.cx0;
                            float dy = twoFinger.cy - twoFinger.cy0;
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            float swipePx = CONFIG_SWIPE * R;

                            if (dist >= swipePx) {
                                if (!twoFinger.isRunning) {
                                    twoFinger.isRunning = true;
                                    pressKey(KEY_Y); // Ativa corrida do Mario!
                                    flashButton("RUN");
                                }
                                int newDir = getDirectionKey(dx, dy, twoFinger.dirKey);
                                if (newDir != twoFinger.dirKey) {
                                    if (twoFinger.dirKey != 0) releaseKey(twoFinger.dirKey);
                                    twoFinger.dirKey = newDir;
                                    pressKey(twoFinger.dirKey);
                                }
                            }
                        }
                        continue;
                    }

                    // 2. Processamento de UM DEDO (Walk & D-pad)
                    if ("pending".equals(tch.kind) || "dir".equals(tch.kind)) {
                        float dx = x - tch.x0;
                        float dy = y - tch.y0;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        float swipePx = CONFIG_SWIPE * R;
                        float maxPx = CONFIG_JOYSTICK_MAX * R;

                        if (dist > maxPx) {
                            tch.x0 = x - (dx / dist) * maxPx;
                            tch.y0 = y - (dy / dist) * maxPx;
                            dx = x - tch.x0;
                            dy = y - tch.y0;
                            dist = maxPx;
                        }

                        if ("pending".equals(tch.kind)) {
                            if (dist >= swipePx) {
                                tch.kind = "dir";
                                setDirection(getDirectionKey(dx, dy, 0));
                            }
                        } else {
                            if (dist < swipePx * 0.5f) {
                                if (currentDirKey != 0) setDirection(0);
                            } else if (dist >= swipePx * 0.8f || currentDirKey != 0) {
                                setDirection(getDirectionKey(dx, dy, currentDirKey));
                            }
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                TouchRecord tch = touches.remove(pointerId);
                if (tch != null) {
                    if ("aux".equals(tch.kind)) {
                        final int keyToRelease = tch.key;
                        long held = now - tch.t0;
                        if (held < CONFIG_TAP_HOLD_MS) {
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    releaseKey(keyToRelease);
                                }
                            }, CONFIG_TAP_HOLD_MS - held);
                        } else {
                            releaseKey(keyToRelease);
                        }
                    } else if ("two".equals(tch.kind) && twoFinger != null) {
                        if (twoFinger.isRunning) {
                            releaseKey(KEY_Y); // Solta corrida do Mario
                        }
                        if (twoFinger.dirKey != 0) {
                            releaseKey(twoFinger.dirKey);
                        }
                        boolean hasMoreTwo = false;
                        for (TouchRecord o : touches.values()) {
                            if ("two".equals(o.kind)) hasMoreTwo = true;
                        }
                        if (!hasMoreTwo) twoFinger = null;
                        if (primaryTouchId != null && primaryTouchId == pointerId) {
                            primaryTouchId = null;
                        }
                    } else if (primaryTouchId != null && primaryTouchId == pointerId) {
                        primaryTouchId = null;
                        if ("pending".equals(tch.kind)) {
                            float dx = tch.x - tch.x0;
                            float dy = tch.y - tch.y0;
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            float swipePx = CONFIG_SWIPE * R;
                            if (dist >= swipePx * 0.65f) {
                                setDirection(getDirectionKey(dx, dy, 0));
                                endDirection();
                            } else {
                                String btn = getButtonAt(tch.x0, tch.y0);
                                tapKey(getKeyCode(btn), btn);
                            }
                        } else if ("dir".equals(tch.kind)) {
                            endDirection();
                        }
                    }
                }
                lastTouchEndTime = now;
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                lastTouchEndTime = now;
                reset();
                break;
            }
        }

        return true;
    }

    public void reset() {
        endDirection();
        if (twoFinger != null) {
            if (twoFinger.isRunning) releaseKey(KEY_Y);
            if (twoFinger.dirKey != 0) releaseKey(twoFinger.dirKey);
            twoFinger = null;
        }
        for (TouchRecord tch : touches.values()) {
            if ("aux".equals(tch.kind)) {
                releaseKey(tch.key);
            }
        }
        touches.clear();
        primaryTouchId = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float R = Math.min(cx, cy);
        float lw = Math.max(4f, R * 0.04f);
        arcPaint.setStrokeWidth(lw);

        long now = System.currentTimeMillis();

        // 0. Escurecimento de tela para prints (ativado somente quando SHOW_CONTROLS_FOR_PRINT == true)
        if (SHOW_CONTROLS_FOR_PRINT) {
            canvas.drawColor(Color.argb(145, 0, 0, 0)); // ~57% preto para contraste máximo
            if (vignetteShader == null || lastVignetteR != R) {
                vignetteShader = new RadialGradient(
                    cx, cy, R,
                    new int[] { Color.argb(0, 0, 0, 0), Color.argb(110, 0, 0, 0), Color.argb(215, 0, 0, 0) },
                    new float[] { 0.38f, 0.70f, 1.0f },
                    Shader.TileMode.CLAMP
                );
                scrimPaint.setShader(vignetteShader);
                lastVignetteR = R;
            }
            canvas.drawCircle(cx, cy, R, scrimPaint);
        }

        // 1. Arco direcional atual
        int activeDir = currentDirKey != 0 ? currentDirKey : (twoFinger != null ? twoFinger.dirKey : 0);
        if (activeDir != 0) {
            float dirAngle = 0;
            switch (activeDir) {
                case KEY_RIGHT: dirAngle = 0; break;
                case KEY_DOWN:  dirAngle = 90; break;
                case KEY_LEFT:  dirAngle = 180; break;
                case KEY_UP:    dirAngle = 270; break;
            }
            if (twoFinger != null && twoFinger.isRunning) {
                arcPaint.setColor(Color.argb(230, 255, 180, 0)); // Laranja correndo
            } else {
                arcPaint.setColor(Color.argb(220, 255, 255, 255)); // Branco andando
            }
            ovalRect.set(cx - (R - lw), cy - (R - lw), cx + (R - lw), cy + (R - lw));
            canvas.drawArc(ovalRect, dirAngle - 28, 56, false, arcPaint);
        }

        // 2. Todos os botões na borda circular (L, SELECT, R, B, A, START)
        // Mantidos visíveis (~160 alpha) para exibir a interface completa na tela
        // e iluminando com brilho máximo (240+) ao tocar.
        String[] defaultButtons = {"L", "SELECT", "R", "B", "A", "START"};
        float[] defaultAngles = {
            (float) (-3 * Math.PI / 4),
            (float) (-Math.PI / 2),
            (float) (-Math.PI / 4),
            (float) Math.PI,
            0f,
            (float) (Math.PI / 2)
        };
        int[] defaultColors = {
            Color.rgb(255, 217, 89),
            Color.rgb(217, 158, 255),
            Color.rgb(255, 217, 89),
            Color.rgb(255, 115, 102),
            Color.rgb(102, 242, 140),
            Color.rgb(115, 184, 255)
        };

        boolean hasActiveFlashes = false;
        for (int i = 0; i < defaultButtons.length; i++) {
            String label = defaultButtons[i];
            float angle = defaultAngles[i];
            int color = defaultColors[i];

            int alpha = 0;
            FlashArc flash = activeFlashes.get(label);
            if (flash != null) {
                long elapsed = now - flash.startTime;
                if (elapsed < FLASH_DURATION_MS) {
                    hasActiveFlashes = true;
                    float progress = 1.0f - (float) elapsed / FLASH_DURATION_MS;
                    alpha = (int) (140 + progress * 115);
                }
            }

            if (SHOW_CONTROLS_FOR_PRINT) {
                alpha = Math.max(alpha, 235);
            }
            if (alpha <= 0) continue;

            int colorWithAlpha = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
            arcPaint.setColor(colorWithAlpha);

            ovalRect.set(cx - (R - lw), cy - (R - lw), cx + (R - lw), cy + (R - lw));
            float deg = (float) Math.toDegrees(angle);
            float span = label.length() > 1 ? 38 : 26;

            // Sombra escura sob o arco
            shadowPaint.setStrokeWidth(lw + 3.2f);
            shadowPaint.setColor(Color.argb((int) (alpha * 0.75f), 0, 0, 0));
            canvas.drawArc(ovalRect, deg - span / 2, span, false, shadowPaint);

            canvas.drawArc(ovalRect, deg - span / 2, span, false, arcPaint);

            float labelDist = R - lw * 2.5f;
            float lx = cx + (float) Math.cos(angle) * labelDist;
            float ly = cy + (float) Math.sin(angle) * labelDist + 8;

            // Pílula escura de fundo para o texto nunca sumir em fundos claros
            float tw = textPaint.measureText(label);
            labelRect.set(lx - tw * 0.5f - 6f, ly - 18f, lx + tw * 0.5f + 6f, ly + 6f);
            labelBgPaint.setAlpha((int) (alpha * 0.88f));
            canvas.drawRoundRect(labelRect, 8f, 8f, labelBgPaint);

            textPaint.setColor(Color.argb(Math.min(255, alpha + 35), 255, 255, 255));
            canvas.drawText(label, lx, ly, textPaint);
        }

        if (hasActiveFlashes || SHOW_CONTROLS_FOR_PRINT) {
            postInvalidateOnAnimation();
        }
    }
}
