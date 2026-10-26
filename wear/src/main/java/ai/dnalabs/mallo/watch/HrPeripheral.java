package ai.dnalabs.mallo.watch;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 워치를 '블루투스 표준 심박계'로 만든다.
 * Heart Rate Service(0x180D)의 Heart Rate Measurement(0x2A37)를 광고하고, 연결된 기기(탭)에 1초마다 알림으로 보낸다.
 */
@SuppressLint("MissingPermission")
class HrPeripheral {

    interface Listener { void onPeers(int count, String text); }

    static final UUID HRS = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HRM = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID BSL = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Listener l;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic hrm;
    private BluetoothLeAdvertiser adv;
    private final Set<BluetoothDevice> subscribers = new CopyOnWriteArraySet<>();
    private final Set<BluetoothDevice> connected = new CopyOnWriteArraySet<>();
    private boolean advertising;
    private AdvertiseSettings advSet;
    private AdvertiseData advData, advResp;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    HrPeripheral(Context ctx, Listener l) { this.ctx = ctx; this.l = l; }

    /** @return 오류 메시지 (성공이면 null) */
    String start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) return "블루투스 없음";
        if (!ad.isEnabled()) return "블루투스를 켜 주세요";
        adv = ad.getBluetoothLeAdvertiser();
        if (adv == null) return "이 워치는 블루투스 광고를 지원하지 않아요";

        server = bm.openGattServer(ctx, cb);
        if (server == null) return "블루투스 서버를 열 수 없어요";
        BluetoothGattService s = new BluetoothGattService(HRS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        hrm = new BluetoothGattCharacteristic(HRM, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        hrm.addDescriptor(cccd);
        s.addCharacteristic(hrm);
        BluetoothGattCharacteristic bsl = new BluetoothGattCharacteristic(BSL,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
        bsl.setValue(new byte[]{0x02}); // 착용 위치: 손목
        s.addCharacteristic(bsl);
        server.addService(s);

        advSet = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true).setTimeout(0).build();
        advData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HRS)).setIncludeDeviceName(false).build();
        advResp = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
        startAdv();
        return null;
    }

    private void startAdv() {
        if (adv == null || server == null) return;
        try { adv.stopAdvertising(advCb); } catch (Exception ignored) {}
        try {
            adv.startAdvertising(advSet, advData, advResp, advCb);
        } catch (Exception e) {
            // 기기 이름이 길면 응답 크기를 넘을 수 있어 이름 없이 다시 시도
            try { adv.startAdvertising(advSet, advData, advCb); } catch (Exception ignored) {}
        }
    }

    void stop() {
        h.removeCallbacksAndMessages(null);
        try { if (adv != null && advertising) adv.stopAdvertising(advCb); } catch (Exception ignored) {}
        advertising = false;
        try { if (server != null) for (BluetoothDevice d : connected) server.cancelConnection(d); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        subscribers.clear(); connected.clear();
    }

    int peers() { return subscribers.size(); }

    /** 연결된 기기에 심박을 보낸다 */
    void send(int bpm) {
        if (server == null || hrm == null || subscribers.isEmpty()) return;
        byte[] v = bpm > 255 ? new byte[]{0x01, (byte) (bpm & 0xFF), (byte) (bpm >> 8)} : new byte[]{0x00, (byte) bpm};
        for (BluetoothDevice d : subscribers) {
            try {
                if (Build.VERSION.SDK_INT >= 33) server.notifyCharacteristicChanged(d, hrm, false, v);
                else { hrm.setValue(v); server.notifyCharacteristicChanged(d, hrm, false); }
            } catch (Exception ignored) {}
        }
    }

    private void report() {
        int n = subscribers.size();
        l.onPeers(n, n > 0 ? "탭으로 보내는 중 (" + n + "대)" : advertising ? "탭 연결 기다리는 중" : "블루투스 준비 중");
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) { advertising = true; report(); }
        @Override public void onStartFailure(int code) {
            if (code == ADVERTISE_FAILED_ALREADY_STARTED) { advertising = true; report(); return; }
            advertising = false; l.onPeers(subscribers.size(), "블루투스 광고 실패 (" + code + ")");
        }
    };

    private final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice d, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) connected.add(d);
            else { connected.remove(d); subscribers.remove(d); }
            report();
            // 연결되면 광고가 멈추는 워치가 있어서, 다른 기기·재연결을 위해 광고를 다시 켠다
            h.postDelayed(HrPeripheral.this::startAdv, 500);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int reqId, BluetoothGattDescriptor desc,
                                                       boolean prepared, boolean needResp, int offset, byte[] value) {
            if (CCCD.equals(desc.getUuid())) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers.add(d);
                else subscribers.remove(d);
                report();
            }
            if (needResp && server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, value);
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice d, int reqId, int offset, BluetoothGattDescriptor desc) {
            byte[] v = subscribers.contains(d) ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            if (server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, v);
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice d, int reqId, int offset, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue() == null ? new byte[0] : c.getValue();
            if (server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, v);
        }
    };
}
