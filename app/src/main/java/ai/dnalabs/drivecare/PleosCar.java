package ai.dnalabs.drivecare;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.api.model.area.WindowArea;
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.area.WindowValue;
import ai.pleos.playground.vehicle.constant.control.WiperState;
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit;
import kotlin.Unit;

/** Pleos Vehicle SDK 로 실제 차량(또는 Pleos 에뮬레이터)을 제어한다. */
class PleosCar implements Car {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Vehicle vehicle;
    private Listener l;
    private final SeatArea driver = new SeatArea(SeatValue.ROW_1_LEFT);
    private SeatArea acArea = null, powerArea = null;
    private final WindowArea driverWindow = new WindowArea(WindowValue.ROW_1_LEFT);
    private int windowMin = 0, windowMax = 100;
    private boolean raining = false;

    PleosCar(Context ctx) {
        vehicle = new Vehicle(ctx.getApplicationContext());
        vehicle.initialize();
        h.postDelayed(this::probe, 1200);
    }

    @Override public String name() { return "Pleos 차량"; }
    @Override public boolean isVirtual() { return false; }
    @Override public void setListener(Listener l) { this.l = l; }

    private Unit ok(String what) { h.post(() -> { if (l != null) l.onLog("차량 ✓ " + what); }); return Unit.INSTANCE; }
    private Unit fail(String what, Exception e) {
        h.post(() -> { if (l != null) l.onLog("차량 ✗ " + what + " (" + (e == null ? "" : e.getClass().getSimpleName() + ": " + e.getMessage()) + ")"); });
        return Unit.INSTANCE;
    }

    private void probe() {
        try {
            vehicle.getHVAC().checkHvacAcCapabilities(
                    (java.util.List<SeatArea> a) -> { if (a != null && !a.isEmpty()) acArea = a.get(0); return Unit.INSTANCE; },
                    e -> fail("에어컨 지원 확인", e));
            vehicle.getHVAC().checkHvacPowerCapabilities(
                    (java.util.List<SeatArea> a) -> { if (a != null && !a.isEmpty()) powerArea = a.get(0); return Unit.INSTANCE; },
                    e -> fail("공조 전원 확인", e));
        } catch (Exception e) { fail("공조 확인", e); }
        try {
            vehicle.getWiper().getCurrentWiperState(new WindowArea(WindowValue.FRONT_WINDSHIELD),
                    s -> { raining = s == WiperState.ON; return Unit.INSTANCE; }, e -> Unit.INSTANCE);
        } catch (Exception ignored) {}
        try {
            vehicle.getWindow().checkWindowPositionCapabilities(
                    (java.util.Map<WindowArea, AreaBounds<Integer>> m) -> {
                        AreaBounds<Integer> b = m == null ? null : m.get(driverWindow);
                        if (b == null && m != null && !m.isEmpty()) b = m.values().iterator().next();
                        if (b != null) { windowMin = b.getMinValue(); windowMax = b.getMaxValue(); }
                        return Unit.INSTANCE;
                    }, e -> Unit.INSTANCE);
        } catch (Exception ignored) {}
    }

    @Override public void setPower(boolean on) {
        try { vehicle.getHVAC().setHvacPower(powerArea != null ? powerArea : driver, on, (a, v) -> Unit.INSTANCE, e -> Unit.INSTANCE); }
        catch (Exception ignored) {}
    }

    @Override public void setTemp(float c, boolean highlight) {
        try {
            vehicle.getHVAC().setHvacTemperature(driver, c, TemperatureUnit.CELSIUS,
                    (a, v) -> { h.post(() -> { if (l != null) l.onTemp(v, highlight); }); return highlight ? ok("운전석 온도 " + v + "°C") : Unit.INSTANCE; },
                    e -> fail("온도", e));
        } catch (Exception e) { fail("온도", e); }
    }

    @Override public void setAc(boolean on) {
        try { vehicle.getHVAC().setHvacAc(acArea != null ? acArea : driver, on, (a, v) -> ok(on ? "에어컨 켜짐" : "에어컨 꺼짐"), e -> fail("에어컨", e)); }
        catch (Exception e) { fail("에어컨", e); }
    }

    @Override public void seatHeat(int v) {
        try { vehicle.getSeat().setHvacSeatTemperature(driver, v, (a, x) -> ok(v == 0 ? "시트 열선 끔" : "시트 열선 " + v + "단계"), e -> fail("시트 열선", e)); }
        catch (Exception e) { fail("시트 열선", e); }
    }

    @Override public void seatVent(int v) {
        try { vehicle.getSeat().setHvacSeatVentilation(driver, v, (a, x) -> ok("시트 통풍 " + v + "단계"), e -> fail("시트 통풍", e)); }
        catch (Exception e) { fail("시트 통풍", e); }
    }

    @Override public void wheelHeat(int v) {
        try { vehicle.getSteeringWheel().setHvacSteeringWheelHeat(v, x -> ok(v == 0 ? "핸들 열선 끔" : "핸들 열선 켬"), e -> fail("핸들 열선", e)); }
        catch (Exception e) { fail("핸들 열선", e); }
    }

    @Override public void openWindowSlightly() {
        int pos = windowMin + Math.max(1, (windowMax - windowMin) * 15 / 100);
        try { vehicle.getWindow().setWindowPosition(driverWindow, pos, (a, v) -> ok("운전석 창문 살짝 열림 (" + v + ")"), e -> fail("창문", e)); }
        catch (Exception e) { fail("창문", e); }
    }

    @Override public boolean isRaining() { return raining; }
    @Override public void setRaining(boolean r) { raining = r; }
    @Override public String summary() { return ""; }

    @Override public void release() { h.removeCallbacksAndMessages(null); try { vehicle.release(); } catch (Exception ignored) {} }
}
