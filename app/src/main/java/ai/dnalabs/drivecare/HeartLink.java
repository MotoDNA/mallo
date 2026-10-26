package ai.dnalabs.drivecare;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;
import java.util.UUID;

/**
 * 블루투스 표준 심박 서비스(Heart Rate Service 0x180D)에 연결해 실시간 심박을 받는다.
 * Mallo Watch(갤럭시 워치 앱)뿐 아니라 일반 블루투스 심박 벨트도 연결된다.
 */
@SuppressLint("MissingPermission")
class HeartLink {

    interface Listener {
        void onHeart(int bpm);
        void onLinkState(String text, boolean connected);
    }

    static final UUID HRS = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HRM = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Listener l;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning, wanted, connected;
    private String deviceName = "";
    private long lastData = 0;

    /** 연결은 살아 있는데 심박이 20초 넘게 안 오면(워치 앱을 멈춘 경우 등) 끊고 다시 찾는다 */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            if (connected && System.currentTimeMillis() - lastData > 20000) {
                state("심박 신호가 없어요 · 다시 연결 중", false);
                connected = false;
                if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {} gatt = null; }
                retry(1000);
            }
            h.postDelayed(this, 5000);
        }
    };

    HeartLink(Context ctx, Listener l) { this.ctx = ctx.getApplicationContext(); this.l = l; }

    boolean isConnected() { return connected; }
    boolean isActive() { return wanted; }

    void start() {
        if (!wanted) { h.removeCallbacks(watchdog); h.postDelayed(watchdog, 5000); }
        wanted = true;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { state("이 기기는 블루투스를 지원하지 않아요", false); return; }
        if (!ad.isEnabled()) { state("블루투스를 켜 주세요", false); retry(4000); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { state("블루투스 준비 중…", false); retry(2000); return; }
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HRS)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.singletonList(f), s, scanCb);
            scanning = true;
            state("워치 찾는 중… (워치에서 Mallo Watch를 켜 주세요)", false);
        } catch (Exception e) { state("검색 실패: " + e.getMessage(), false); retry(4000); }
    }

    void stop() {
        wanted = false;
        stopScan();
        h.removeCallbacksAndMessages(null);
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {} gatt = null; }
        connected = false;
        state("워치 연결 꺼짐", false);
    }

    private void stopScan() {
        if (scanning && scanner != null) { try { scanner.stopScan(scanCb); } catch (Exception ignored) {} }
        scanning = false;
    }

    private void retry(long ms) { h.postDelayed(() -> { if (wanted && !connected && gatt == null) start(); }, ms); }

    private void state(String t, boolean c) { h.post(() -> l.onLinkState(t, c)); }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (gatt != null) return;
            stopScan();
            BluetoothDevice d = r.getDevice();
            String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            deviceName = n != null ? n : "심박 센서";
            state(deviceName + " 연결 중…", false);
            if (Build.VERSION.SDK_INT >= 23) gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            else gatt = d.connectGatt(ctx, false, gattCb);
        }
        @Override public void onScanFailed(int code) { scanning = false; state("검색 실패 (" + code + ")", false); retry(4000); }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close(); } catch (Exception ignored) {}
                gatt = null;
                connected = false;
                state("워치 연결이 끊겼어요 · 다시 찾는 중", false);
                if (wanted) retry(2000);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(HRS);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(HRM);
            if (c == null) { state("심박 서비스를 찾지 못했어요", false); g.disconnect(); return; }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            connected = true;
            lastData = System.currentTimeMillis();
            state(deviceName + " 연결됨 · 실시간 심박", true);
        }

        // Android 13 이상
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handle(c, value);
        }
        // Android 12 이하
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) handle(c, c.getValue());
        }
    };

    private void handle(BluetoothGattCharacteristic c, byte[] v) {
        if (!HRM.equals(c.getUuid()) || v == null || v.length < 2) return;
        lastData = System.currentTimeMillis();
        int bpm = parseBpm(v);
        if (bpm > 0) h.post(() -> l.onHeart(bpm));
    }

    /** Heart Rate Measurement(0x2A37) 해석: 첫 바이트 bit0 = 1 이면 16비트 값 */
    static int parseBpm(byte[] v) {
        int flags = v[0] & 0xFF;
        if ((flags & 0x01) != 0 && v.length >= 3) return (v[1] & 0xFF) | ((v[2] & 0xFF) << 8);
        return v[1] & 0xFF;
    }
}
