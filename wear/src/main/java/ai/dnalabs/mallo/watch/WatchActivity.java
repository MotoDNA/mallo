package ai.dnalabs.mallo.watch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 워치 화면: 지금 심박, 탭 연결 상태, 시작/멈춤 버튼 */
public class WatchActivity extends Activity implements HeartService.Ui {

    TextView bpm, sensor, link;
    Button toggle;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(14);
        c.setPadding(pad, dp(28), pad, dp(36));
        sv.addView(c);

        c.addView(t("Mallo Watch", 15, Color.parseColor("#8B93A1"), true));
        bpm = t("--", 46, Color.parseColor("#FF6B81"), true);
        c.addView(bpm);
        c.addView(t("bpm", 13, Color.parseColor("#8B93A1"), false));
        sensor = t("", 11, Color.WHITE, false);
        sensor.setPadding(0, dp(6), 0, 0);
        c.addView(sensor);
        link = t("", 11, Color.parseColor("#4C8DFF"), false);
        c.addView(link);
        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(Color.parseColor("#2A2F38"));
        toggle.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.topMargin = dp(10);
        c.addView(toggle, lp);
        toggle.setOnClickListener(v -> {
            if (HeartService.running) startService(new Intent(this, HeartService.class).setAction(HeartService.STOP));
            else startHeart();
            v.postDelayed(this::refresh, 400);
        });
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        HeartService.ui = this;
        refresh();
        if (!HeartService.running) startHeart();
    }

    @Override protected void onPause() { super.onPause(); if (HeartService.ui == this) HeartService.ui = null; }

    void startHeart() {
        List<String> need = new ArrayList<>();
        need.add(android.Manifest.permission.BODY_SENSORS);
        need.add(android.Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(android.Manifest.permission.BLUETOOTH_ADVERTISE);
            need.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        List<String> ask = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) { requestPermissions(ask.toArray(new String[0]), 1); return; }
        startForegroundService(new Intent(this, HeartService.class));
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        boolean sensorsOk = checkSelfPermission(android.Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
        boolean btOk = Build.VERSION.SDK_INT < 31 || checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        if (sensorsOk && btOk) startForegroundService(new Intent(this, HeartService.class));
        else sensor.setText("심박 센서와 주변 기기 권한을 허용해 주세요");
        refresh();
    }

    void refresh() {
        toggle.setText(HeartService.running ? "멈추기" : "시작하기");
        sensor.setText(HeartService.sensorText);
        link.setText(HeartService.linkText);
        if (HeartService.lastBpm > 0) bpm.setText(String.valueOf(HeartService.lastBpm));
    }

    @Override public void onHeart(int v, String source) { runOnUiThread(() -> bpm.setText(String.valueOf(v))); }
    @Override public void onStatus(String s, String l) { runOnUiThread(() -> { sensor.setText(s); link.setText(l); toggle.setText(HeartService.running ? "멈추기" : "시작하기"); }); }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView t(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }
}
