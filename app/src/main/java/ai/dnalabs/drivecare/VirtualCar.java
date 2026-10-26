package ai.dnalabs.drivecare;

import android.os.Handler;
import android.os.Looper;

/**
 * 차량이 없는 기기(갤럭시 탭 등)용 가상 차량.
 * 실제 차량과 같은 명령을 받아 상태만 바꾸고, 화면의 '가상 차량' 줄에 보여 준다.
 */
class VirtualCar implements Car {
    private final Handler h = new Handler(Looper.getMainLooper());
    private Listener l;

    boolean power = true, ac = false, raining = false;
    float temp = 24f;
    int seatHeat = 1, seatVent = 0, wheelHeat = 1, windowPct = 0;

    @Override public String name() { return "가상 차량"; }
    @Override public boolean isVirtual() { return true; }
    @Override public void setListener(Listener l) { this.l = l; }

    private void later(Runnable r) { h.postDelayed(() -> { r.run(); if (l != null) l.onChanged(); }, 150); }
    private void log(String s) { if (l != null) l.onLog("가상 차량 ✓ " + s); }

    @Override public void setPower(boolean on) { later(() -> power = on); }

    @Override public void setTemp(float c, boolean highlight) {
        later(() -> { temp = c; if (l != null) l.onTemp(c, highlight); if (highlight) log("운전석 온도 " + Math.round(c) + "°C"); });
    }
    @Override public void setAc(boolean on) { later(() -> { ac = on; log(on ? "에어컨 켜짐" : "에어컨 꺼짐"); }); }
    @Override public void seatHeat(int v) { later(() -> { seatHeat = v; log(v == 0 ? "시트 열선 끔" : "시트 열선 " + v + "단계"); }); }
    @Override public void seatVent(int v) { later(() -> { seatVent = v; log("시트 통풍 " + v + "단계"); }); }
    @Override public void wheelHeat(int v) { later(() -> { wheelHeat = v; log(v == 0 ? "핸들 열선 끔" : "핸들 열선 켬"); }); }
    @Override public void openWindowSlightly() { later(() -> { windowPct = 15; log("운전석 창문 살짝 열림 (15%)"); }); }

    @Override public boolean isRaining() { return raining; }
    @Override public void setRaining(boolean r) { raining = r; if (l != null) l.onChanged(); }

    @Override public String summary() {
        return "가상 차량   🌡 " + Math.round(temp) + "°C   ❄ 에어컨 " + (ac ? "켜짐" : "꺼짐")
                + "   🪟 창문 " + (windowPct == 0 ? "닫힘" : windowPct + "% 열림")
                + "   💺 열선 " + seatHeat + " · 통풍 " + seatVent
                + "   🎡 핸들 열선 " + (wheelHeat == 0 ? "꺼짐" : "켜짐")
                + "   ☔ " + (raining ? "비·눈" : "맑음");
    }

    @Override public void release() { h.removeCallbacksAndMessages(null); }
}
