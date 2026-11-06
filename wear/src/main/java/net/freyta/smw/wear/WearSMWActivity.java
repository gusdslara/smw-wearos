package net.freyta.smw.wear;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Atividade principal de Super Mario World para Wear OS:
 *
 * 1. 4 Níveis de Zoom via Bezel / Coroa:
 *    - Nível 0 (0.76x): "Totally visible" (tela do SNES inteira sem nenhum corte no círculo).
 *    - Nível 1 (0.88x): "A little bit less zoom" (intermediário confortável).
 *    - Nível 2 (1.00x): "The way it is now" (largura total da tela).
 *    - Nível 3 (1.15x): "No top border" (zoom que encosta e elimina bordas verticais).
 *
 * 2. Movimento de Braço para Pulo Máximo (Arm Jump):
 *    - Detecta sacudida rápida para cima/baixo com o acelerômetro e executa High Jump (B segurado 340ms).
 *
 * 3. Corrida com 2 Dedos (Two Finger Run):
 *    - Deslizar com 2 dedos ativa o Dash / Corrida do Mario e direciona ao mesmo tempo.
 *
 * 4. Botão físico Back -> START.
 */
public class WearSMWActivity extends SDLActivity implements SensorEventListener {

    private WearSmartTouchOverlay touchOverlay;
    private float rotaryAccum = 0f;
    private static final float ROTARY_STEP = 0.8f;
    private static final long WHEEL_TOUCH_GUARD_MS = 400; // Guarda de 400ms para evitar zoom acidental ao andar

    // 4 Níveis de Zoom
    private static final float[] ZOOM_LEVELS = new float[]{ 0.76f, 0.88f, 1.00f, 1.15f };
    private int currentZoomIndex = 2; // Inicia em 1.00x ("the way it is now")

    // Sensor de Acelerômetro para Pulo
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastJumpGestureTime = 0;
    private float lastZ = 9.8f;
    private float lastY = 0f;

    // Primeiro uso: Transferência de ROM via Wi-Fi + QR Code
    private RomInbox romInbox;
    private RomTransferView transferView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWearFiles();
        inflateOverlay();
        checkAndShowRomTransferIfNeeded();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    private void checkAndShowRomTransferIfNeeded() {
        File externalDir = getExternalFilesDir(null);
        if (externalDir == null) return;

        File dat = new File(externalDir, "smw_assets.dat");
        File sfc = new File(externalDir, "smw.sfc");

        // Se nenhum arquivo de jogo existir, abre a tela de QR Code para transferência
        if (!dat.exists() && !sfc.exists()) {
            final ViewGroup rootView = getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) return;

            transferView = new RomTransferView(this);
            rootView.addView(transferView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            romInbox = new RomInbox(this, externalDir, new RomInbox.Listener() {
                @Override
                public void onServerReady(String url, android.graphics.Bitmap qrBitmap) {
                    if (transferView != null) {
                        transferView.setQrCode(url, qrBitmap);
                    }
                }

                @Override
                public void onProgress(int percent) {
                    if (transferView != null) {
                        transferView.setProgress(percent);
                    }
                }

                @Override
                public void onFileReceived(File file) {
                    if (transferView != null) {
                        transferView.setStatus("ROM recebida com sucesso! Reiniciando...", android.graphics.Color.rgb(102, 242, 140));
                    }
                    if (romInbox != null) {
                        romInbox.stop();
                    }
                    // Reinicia a atividade para o SDL carregar os novos assets
                    rootView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            android.content.Intent intent = getIntent();
                            finish();
                            startActivity(intent);
                        }
                    }, 1200);
                }

                @Override
                public void onError(String msg) {
                    if (transferView != null) {
                        transferView.setStatus(msg, android.graphics.Color.rgb(255, 115, 102));
                    }
                }
            });
            romInbox.start();
        }
    }

    private void setupWearFiles() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            return;
        }
        File externalDir = getExternalFilesDir(null);
        if (externalDir != null) {
            File configFile = new File(externalDir, "smw.ini");
            File savesFolder = new File(externalDir, "saves");
            File savesRef = new File(savesFolder, "ref");
            File playthrough = new File(externalDir, "playthrough");
            File datNotice = new File(externalDir, "PLACE smw_assets.dat HERE");

            savesFolder.mkdirs();
            savesRef.mkdirs();
            playthrough.mkdirs();

            try {
                datNotice.createNewFile();
                AssetCopyUtil.copyAssetsToExternal(this, "saves/ref", savesRef.getAbsolutePath());

                // Se houver smw_assets.dat ou smw.sfc empacotados nos assets (build de dev), copia automaticamente
                File datDest = new File(externalDir, "smw_assets.dat");
                if (!datDest.exists()) {
                    copyAssetIfExists("smw_assets.dat", datDest);
                }
                File sfcDest = new File(externalDir, "smw.sfc");
                if (!sfcDest.exists()) {
                    copyAssetIfExists("smw.sfc", sfcDest);
                }

                if (!configFile.exists()) {
                    try (InputStream in = getAssets().open("smw.ini");
                         FileOutputStream out = new FileOutputStream(configFile)) {
                        byte[] buf = new byte[1024];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void copyAssetIfExists(String assetName, File destFile) {
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (IOException ignored) {
            // Em builds de produção sem a ROM embutida, não faz nada e usará RomInbox / QR Code
        }
    }

    public void inflateOverlay() {
        touchOverlay = new WearSmartTouchOverlay(this);
        ViewGroup rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addView(touchOverlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private void applyZoom(int newIndex) {
        if (newIndex < 0) newIndex = 0;
        if (newIndex >= ZOOM_LEVELS.length) newIndex = ZOOM_LEVELS.length - 1;

        if (newIndex != currentZoomIndex) {
            currentZoomIndex = newIndex;
            float scale = ZOOM_LEVELS[currentZoomIndex];

            View surface = SDLActivity.mSurface;
            if (surface != null) {
                surface.setPivotX(surface.getWidth() / 2f);
                surface.setPivotY(surface.getHeight() / 2f);
                surface.setScaleX(scale);
                surface.setScaleY(scale);
            }

            if (touchOverlay != null) {
                touchOverlay.buzz(25);
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Coroa / Bezel rotatório gerencia os 4 níveis de zoom
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            // Touch Guard idêntico ao gen1recomp (WatchGestures.lua):
            // Se o usuário está com o dedo na tela andando ou acabou de soltar nos últimos 400ms,
            // o touch-bezel (ex: Galaxy Watch) ou a curvatura do arrasto dispara falsos eventos de coroa.
            // Ignoramos e resetamos o acumulador para evitar zoom acidental.
            if (touchOverlay != null && touchOverlay.isTouchGuarded(WHEEL_TOUCH_GUARD_MS)) {
                rotaryAccum = 0f;
                return true;
            }

            float vScroll = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (vScroll != 0) {
                rotaryAccum += vScroll;
                if (Math.abs(rotaryAccum) >= ROTARY_STEP) {
                    if (rotaryAccum > 0) {
                        applyZoom(currentZoomIndex + 1); // Zoom in
                    } else {
                        applyZoom(currentZoomIndex - 1); // Zoom out
                    }
                    rotaryAccum = 0f;
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float deltaZ = Math.abs(z - lastZ);
            float deltaY = y - lastY; // Variação no eixo Y (apontando para cima na tela / em direção ao jogador)
            float absDeltaY = Math.abs(deltaY);
            lastZ = z;
            lastY = y;

            long now = System.currentTimeMillis();

            // 1. Movimento forte de braço/pulso: High Jump (B segurado 340ms)
            if (deltaZ > 5.5f || absDeltaY > 6.0f) {
                if (now - lastJumpGestureTime > 380) {
                    lastJumpGestureTime = now;
                    if (touchOverlay != null) {
                        touchOverlay.triggerArmHighJump();
                    }
                }
            }
            // 2. Leve tilt na direção do jogador (pulso inclina na sua direção / tick em Y):
            // Um pequeno pulso rápido no botão B (Short Hop / tap leve de 60ms)
            else if (deltaY > 2.2f && deltaY <= 5.5f && absDeltaY > Math.abs(x) * 0.8f) {
                if (now - lastJumpGestureTime > 260) {
                    lastJumpGestureTime = now;
                    if (touchOverlay != null) {
                        touchOverlay.triggerShortHop();
                    }
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            SDLActivity.onNativeKeyDown(WearSmartTouchOverlay.KEY_START);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            SDLActivity.onNativeKeyUp(WearSmartTouchOverlay.KEY_START);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (touchOverlay != null) {
            touchOverlay.reset();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (romInbox != null) {
            romInbox.stop();
            romInbox = null;
        }
    }
}
