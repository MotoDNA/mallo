package ai.dnalabs.mallo.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.health.services.client.ExerciseClient;
import androidx.health.services.client.ExerciseUpdateCallback;
import androidx.health.services.client.HealthServices;
import androidx.health.services.client.MeasureCallback;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.BatchingMode;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.DeltaDataType;
import androidx.health.services.client.data.ExerciseCapabilities;
import androidx.health.services.client.data.ExerciseConfig;
import androidx.health.services.client.data.ExerciseLapSummary;
import androidx.health.services.client.data.ExerciseType;
import androidx.health.services.client.data.ExerciseUpdate;
import androidx.health.services.client.data.SampleDataPoint;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 워치 심박을 계속 측정해서(화면이 꺼져도) 블루투스로 탭에 보내는 서비스.
 * 1순위: Health Services 운동 측정(ExerciseClient) — 화면이 꺼져도 1초 간격
 * 2순위: 실시간 측정(MeasureClient) — 운동 측정을 쓸 수 없을 때
 */
public class HeartService extends Service implements HrPeripheral.Listener {

    interface Ui { void onHeart(int bpm, String source); void onStatus(String sensor, String link); }

    static final String STOP = "ai.dnalabs.mallo.watch.STOP";
    static volatile Ui ui;
    static volatile boolean running;
    static volatile int lastBpm;
    static volatile String sensorText = "", linkText = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Executor main = h::post;
    private HrPeripheral ble;
    private ExerciseClient exercise;
    private MeasureClient measure;
    private boolean usingExercise, usingMeasure;
    private long lastAt;
    private PowerManager.WakeLock wake;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        if (running) return START_STICKY;
        running = true;
        goForeground();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mallo:heart");
        wake.acquire(6 * 60 * 60 * 1000L);  // 최대 6시간

        ble = new HrPeripheral(this, this);
        String err = ble.start();
        if (err != null) onPeers(0, err);

        exercise = HealthServices.getClient(this).getExerciseClient();
        measure = HealthServices.getClient(this).getMeasureClient();
        startExercise();
        h.post(sendTick);
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("hr", "심박 송신", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, WatchActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "hr")
                .setContentTitle("Mallo Watch")
                .setContentText("심박을 탭으로 보내는 중")
                .setSmallIcon(android.R.drawable.star_on)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- 1순위: 운동 측정 ----------
    private void startExercise() {
        setSensor("심박 센서 준비 중…");
        Futures.addCallback(exercise.getCapabilitiesAsync(), new FutureCallback<ExerciseCapabilities>() {
            @Override public void onSuccess(ExerciseCapabilities caps) {
                ExerciseType type = null;
                ExerciseType[] prefer = {ExerciseType.WORKOUT, ExerciseType.YOGA, ExerciseType.WALKING, ExerciseType.STRETCHING};
                for (ExerciseType t : prefer) {
                    if (caps.getSupportedExerciseTypes().contains(t)
                            && caps.getExerciseTypeCapabilities(t).getSupportedDataTypes().contains(DataType.HEART_RATE_BPM)) { type = t; break; }
                }
                if (type == null) { startMeasure("운동 측정 미지원"); return; }
                exercise.setUpdateCallback(main, exerciseCb);
                ExerciseConfig.Builder b = ExerciseConfig.builder(type)
                        .setDataTypes(Collections.singleton(DataType.HEART_RATE_BPM))
                        .setIsAutoPauseAndResumeEnabled(false)
                        .setIsGpsEnabled(false);
                // 화면이 꺼지면 워치가 심박을 모아서(수십 초~분 단위) 보내므로, 5초마다 보내도록 요청한다
                boolean fast = false;
                try {
                    if (caps.getSupportedBatchingModeOverrides().contains(BatchingMode.HEART_RATE_5_SECONDS)) {
                        b.setBatchingModeOverrides(Collections.singleton(BatchingMode.HEART_RATE_5_SECONDS));
                        fast = true;
                    }
                } catch (Throwable ignored) {}
                final boolean fastFinal = fast;
                ExerciseConfig cfg = b.build();
                Futures.addCallback(exercise.startExerciseAsync(cfg), new FutureCallback<Void>() {
                    @Override public void onSuccess(Void v) { usingExercise = true; setSensor(fastFinal ? "심박 측정 중 (화면 꺼져도 5초마다)" : "심박 측정 중 (화면 꺼지면 느려질 수 있음)"); }
                    @Override public void onFailure(Throwable t) { startMeasure("운동 측정 시작 실패: " + t.getMessage()); }
                }, main);
            }
            @Override public void onFailure(Throwable t) { startMeasure("기능 확인 실패: " + t.getMessage()); }
        }, main);
    }

    private final ExerciseUpdateCallback exerciseCb = new ExerciseUpdateCallback() {
        @Override public void onExerciseUpdateReceived(ExerciseUpdate update) {
            List<SampleDataPoint<Double>> hr = update.getLatestMetrics().getData(DataType.HEART_RATE_BPM);
            if (!hr.isEmpty()) onBpm(hr.get(hr.size() - 1).getValue());
        }
        @Override public void onLapSummaryReceived(ExerciseLapSummary s) {}
        @Override public void onRegistered() {}
        @Override public void onRegistrationFailed(Throwable t) { startMeasure("운동 측정 등록 실패"); }
        @Override public void onAvailabilityChanged(DataType<?, ?> type, Availability a) {}
    };

    // ---------- 2순위: 실시간 측정 ----------
    private void startMeasure(String why) {
        if (usingMeasure) return;
        usingMeasure = true;
        setSensor("실시간 측정 사용 (" + why + ")");
        measure.registerMeasureCallback(DataType.HEART_RATE_BPM, main, measureCb);
    }

    private final MeasureCallback measureCb = new MeasureCallback() {
        @Override public void onAvailabilityChanged(DeltaDataType<?, ?> type, Availability a) {}
        @Override public void onDataReceived(DataPointContainer data) {
            List<SampleDataPoint<Double>> hr = data.getData(DataType.HEART_RATE_BPM);
            if (!hr.isEmpty()) onBpm(hr.get(hr.size() - 1).getValue());
        }
    };

    private void onBpm(double v) {
        int bpm = (int) Math.round(v);
        if (bpm <= 0) return;
        lastBpm = bpm;
        Log.d("MalloWatch", "bpm=" + bpm + (usingExercise ? " (exercise)" : " (measure)"));
        lastAt = System.currentTimeMillis();
        Ui u = ui;
        if (u != null) u.onHeart(bpm, usingExercise ? "운동 측정" : "실시간 측정");
    }

    /** 1초마다 최근 심박을 탭으로 보낸다 (센서 값이 드문드문 와도 탭 화면이 끊기지 않게) */
    private final Runnable sendTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (lastBpm > 0 && System.currentTimeMillis() - lastAt < 10000 && ble != null) ble.send(lastBpm);
            h.postDelayed(this, 1000);
        }
    };

    private void setSensor(String s) { sensorText = s; Ui u = ui; if (u != null) u.onStatus(sensorText, linkText); }

    @Override public void onPeers(int count, String text) {
        h.post(() -> { linkText = text; Ui u = ui; if (u != null) u.onStatus(sensorText, linkText); });
    }

    private void shutdown() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { if (usingExercise) { exercise.endExerciseAsync(); exercise.clearUpdateCallbackAsync(exerciseCb); } } catch (Exception ignored) {}
        try { if (usingMeasure) measure.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCb); } catch (Exception ignored) {}
        usingExercise = usingMeasure = false;
        if (ble != null) ble.stop();
        if (wake != null && wake.isHeld()) wake.release();
        lastBpm = 0;
        sensorText = "멈춤"; linkText = "";
        Ui u = ui; if (u != null) u.onStatus(sensorText, linkText);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { if (running) shutdown(); super.onDestroy(); }
}
