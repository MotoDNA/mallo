package ai.dnalabs.drivecare;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.ErrorType;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.listener.OnInitializeListener;
import ai.pleos.playground.tts.listener.EventListener;
import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.listener.ResultListener;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.control.MoveControl;
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit;
import kotlin.Unit;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

/**
 * 데모: 스마트워치 건강 신호(시뮬레이션) + 주행 정보로 피로도를 계산하고,
 * 위험 수준이 되면 음성과 화면으로 안전운전 경고를 보여준다.
 * 1초 = 주행 1분으로 빠르게 흘러간다.
 */
public class MainActivity extends Activity {

    // ---- 색상 ----
    static final int BG = Color.parseColor("#0E1116");
    static final int CARD = Color.parseColor("#181C23");
    static final int LINE = Color.parseColor("#2A2F38");
    static final int INK = Color.parseColor("#F2F4F7");
    static final int QUIET = Color.parseColor("#8B93A1");
    static final int GOOD = Color.parseColor("#3DD68C");
    static final int WARN = Color.parseColor("#F5B841");
    static final int BAD = Color.parseColor("#FF5A5F");
    static final int ACCENT = Color.parseColor("#4C8DFF");

    // ---- 시뮬레이션 상태 ----
    final Handler handler = new Handler(Looper.getMainLooper());
    final Random rnd = new Random(7);
    final double sleepHours = 5.2;     // 어젯밤 수면 (워치)
    final double baselineHr = 74;      // 출발 시 심박
    int minute = 0;                    // 연속 주행 시간(분)
    double hr = baselineHr;
    double hrv = 32;                   // ms (RMSSD)
    double restAreaKm = 23;
    int snoozeUntil = -1;
    boolean running = false;
    boolean alertShown = false;
    int tickMs = 1000;

    TextToSpeech tts;
    boolean ttsReady = false;
    SpeechToText stt;
    boolean sttReady = false;
    Runnable pendingYes, pendingNo;   // 지금 대답을 기다리는 질문
    boolean listenAfterSpeak = false;
    TextView micState;
    Vehicle vehicle;
    boolean vehicleReady = false;
    final SeatArea driver = new SeatArea(SeatValue.ROW_1_LEFT);
    SeatArea acArea = null, fanArea = null, powerArea = null;   // 차량이 지원하는 공조 영역 (기능 확인으로 채움)

    // ---- 화면 요소 ----
    TextView hrValue, hrvValue, sleepValue, driveValue, speedValue, restValue;
    TextView scoreValue, scoreLabel, statusLine, ttsState, logView;
    View scoreBar;
    FrameLayout scoreTrack;
    FrameLayout overlay;
    TextView overlayTitle, overlayBody;
    LinearLayout cautionBanner;
    TextView cautionText;
    final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        // Pleos TextToSpeech SDK (기기 내 합성)
        tts = new TextToSpeech(getApplicationContext(), Mode.ON_DEVICE);
        tts.initialize(new OnInitializeListener() {
            @Override public void onSuccess() {
                tts.setLocale(Locale.KOREA);
                ttsReady = true;
                runOnUiThread(() -> updateLinkState(null));
            }
            @Override public void onFailure(ErrorType e) {
                runOnUiThread(() -> updateLinkState("음성 연결 실패: " + e));
            }
        });
        tts.addEventListener(new EventListener() {
            @Override public void onStart() {}
            @Override public void onReady() {}
            @Override public void onDone() { runOnUiThread(() -> { if (listenAfterSpeak) { listenAfterSpeak = false; startListening(); } }); }
            @Override public void onError(ErrorType e) { runOnUiThread(() -> { if (listenAfterSpeak) { listenAfterSpeak = false; startListening(); } }); }
            @Override public void onStop() {}
            @Override public void onUpdatedRms(double v) {}
        });
        // Pleos SpeechToText SDK (기기 내 인식)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
        }
        stt = new SpeechToText(getApplicationContext(), ai.pleos.playground.stt.constant.Mode.ON_DEVICE);
        stt.initialize(new ai.pleos.playground.stt.listener.OnInitializeListener() {
            @Override public void onSuccess() {
                stt.setLocale(Locale.KOREA);
                sttReady = true;
                runOnUiThread(() -> updateLinkState(null));
            }
            @Override public void onFailure(ai.pleos.playground.stt.constant.ErrorType e) {
                runOnUiThread(() -> updateLinkState("음성 인식 연결 실패: " + e));
            }
        });
        stt.addListener(new ResultListener() {
            @Override public void onUpdated(String text, boolean isFinal) {
                runOnUiThread(() -> {
                    micState.setText("🎤 \"" + text + "\"");
                    if (isFinal) handleAnswer(text);
                });
            }
            @Override public void onUpdatedEpdData(long a, long b) {}
            @Override public void onStartedRecognition() { runOnUiThread(() -> micState.setText("🎤 듣고 있어요… \"응\" 또는 \"아니\"라고 말해 주세요")); }
            @Override public void onEndedRecognition() {}
            @Override public void onError(ai.pleos.playground.stt.constant.ErrorType e) {
                runOnUiThread(() -> { listening = false; micState.setText("🎤 잘 못 들었어요. 버튼으로 답해 주세요 (" + e + ")"); });
            }
            @Override public void onReady() {}
            @Override public void onUpdatedRms(double v) {}
        });
        // Pleos Vehicle SDK
        try {
            vehicle = new Vehicle(getApplicationContext());
            vehicle.initialize();
            vehicleReady = true;
            handler.postDelayed(this::probeHvacAreas, 1200);
        } catch (Exception e) {
            updateLinkState("차량 연결 실패: " + e.getMessage());
        }
        updateLinkState(null);
        render();
        handler.postDelayed(this::start, 1500);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (stt != null) stt.release();
        if (tts != null) tts.release();
        if (vehicle != null) vehicle.release();
        super.onDestroy();
    }

    // ================= 시뮬레이션 =================
    void start() {
        if (running) return;
        running = true;
        addLog("주행 시작 · 워치 연결됨 (시뮬레이션 데이터)");
        speak("출발합니다. 어젯밤 수면이 5시간 12분으로 짧았어요. 피곤하면 언제든 말씀해 주세요.");
        handler.post(tick);
    }

    void reset() {
        handler.removeCallbacks(tick);
        running = false; alertShown = false; snoozeUntil = -1;
        minute = 0; hr = baselineHr; hrv = 32; restAreaKm = 23; tickMs = 1000;
        log.setLength(0);
        overlay.setVisibility(View.GONE);
        cautionBanner.setVisibility(View.GONE);
        render();
        handler.postDelayed(this::start, 500);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            // 질문에 대답을 기다리는 동안에는 시간을 멈춘다 (질문이 겹치지 않게)
            if (pendingYes != null) { if (running) handler.postDelayed(this, tickMs); return; }
            minute++;
            // 졸음이 오면 심박은 서서히 떨어지고 HRV(부교감)는 올라간다 — 데모용 단순 모델
            double drowsy = Math.min(1.0, Math.max(0, (minute - 30) / 70.0));
            hr = baselineHr - 13 * drowsy + rnd.nextGaussian() * 1.2;
            hrv = 32 + 18 * drowsy + rnd.nextGaussian() * 1.5;
            if (!alertShown) restAreaKm -= 100 / 60.0; // 시속 100km
            if (restAreaKm < 1) restAreaKm = 24 + rnd.nextInt(6);
            render();

            int score = fatigueScore();
            boolean snoozed = minute < snoozeUntil;
            if (score >= 75 && !alertShown && !snoozed) {
                showAlert(score);
            } else if (score >= 50 && score < 75 && cautionBanner.getVisibility() != View.VISIBLE && !alertShown) {
                cautionBanner.setVisibility(View.VISIBLE);
                cautionText.setText("피로 신호가 조금씩 보여요. 창문을 열거나 환기해 보세요.");
                addLog(minute + "분 · 주의 단계 (피로도 " + score + ")");
                ask("피로 신호가 조금씩 보여요",
                        "실내 온도를 21°C로 조금 낮춰 볼까요?",
                        "피로 신호가 조금씩 보여요. 실내 온도를 조금 낮춰 볼까요?",
                        "낮춰 줘", "괜찮아요",
                        () -> {
                            addLog(minute + "분 · 운전자 동의 → 온도 21°C");
                            if (vehicleReady) vehicle.getHVAC().setHvacTemperature(driver, 21f, TemperatureUnit.CELSIUS,
                                    (a, v) -> ok("운전석 온도 " + v + "°C"), e -> fail("온도", e));
                        },
                        () -> addLog(minute + "분 · 운전자 거절 → 변경 없음"));
            }
            if (running) handler.postDelayed(this, tickMs);
        }
    };

    int fatigueScore() {
        double s = 0;
        if (sleepHours < 6) s += (6 - sleepHours) * 15;          // 수면 부족: 최대 ~25
        s += Math.min(40, minute / 120.0 * 40);                 // 연속 운전: 2시간이면 40
        s += Math.max(0, Math.min(35, (baselineHr - hr) * 2.7)); // 심박 하락: 최대 35
        return (int) Math.max(0, Math.min(100, Math.round(s)));
    }

    void showAlert(int score) {
        alertShown = true;
        cautionBanner.setVisibility(View.GONE);
        int drop = (int) Math.round(baselineHr - hr);
        String driveText = fmtDuration(minute);
        String km = String.valueOf((int) Math.round(restAreaKm));
        overlayIcon.setText("⚠");
        overlayIcon.setTextColor(BAD);
        yesBtn.setText("휴게소 안내");
        yesBtn.setBackground(roundStroke(BAD, BAD, 24));
        noBtn.setText("괜찮아요");
        pendingYes = this::acceptRest; pendingNo = this::snooze;
        yesBtn.setOnClickListener(v -> answer(true, "버튼"));
        noBtn.setOnClickListener(v -> answer(false, "버튼"));
        micState.setText(sttReady ? "🎤 질문이 끝나면 말로 답할 수 있어요" : "");
        listenSoon(overlayBody.getText().toString() + "졸음 신호가 감지됐어요");
        overlayTitle.setText("졸음 신호가 감지됐어요");
        overlayBody.setText("심박이 출발 때보다 " + drop + " bpm 낮아졌고,\n"
                + driveText + "째 쉬지 않고 운전 중이에요.\n"
                + km + "km 앞 휴게소에서 쉬어 가시겠어요?");
        overlay.setVisibility(View.VISIBLE);
        addLog(minute + "분 · 경고 (피로도 " + score + ", 심박 -" + drop + ")");
        speak("졸음 신호가 감지됐어요. " + driveText + "째 쉬지 않고 운전 중이에요. "
                + km + "킬로미터 앞 휴게소에서 쉬어 가시겠어요?");
    }

    Button yesBtn, noBtn;
    TextView overlayIcon;

    /** 운전자에게 먼저 묻고, 동의할 때만 실행한다. */
    void ask(String title, String body, String voice, String yes, String no, Runnable onYes, Runnable onNo) {
        overlayIcon.setText("?");
        overlayIcon.setTextColor(ACCENT);
        overlayTitle.setText(title);
        overlayBody.setText(body);
        yesBtn.setText(yes);
        yesBtn.setBackground(roundStroke(ACCENT, ACCENT, 24));
        noBtn.setText(no);
        pendingYes = onYes; pendingNo = onNo;
        yesBtn.setOnClickListener(v -> answer(true, "버튼"));
        noBtn.setOnClickListener(v -> answer(false, "버튼"));
        micState.setText(sttReady ? "🎤 질문이 끝나면 말로 답할 수 있어요" : "");
        micState.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
        if (ttsReady) speak(voice);
        listenSoon(ttsReady ? voice : "");
    }

    boolean listening = false;
    /** 안내 음성이 끝날 즈음(글자 수로 추정) 듣기 시작. 음성 종료 이벤트가 먼저 오면 그때 시작. */
    void listenSoon(String spoken) {
        listening = false;
        listenAfterSpeak = sttReady;
        long wait = 600 + (long) (spoken.length() * 170L);
        handler.postDelayed(() -> { if (listenAfterSpeak) { listenAfterSpeak = false; startListening(); } }, wait);
    }

    void startListening() {
        if (!sttReady || pendingYes == null || listening) return;
        listening = true;
        try { stt.request(); } catch (Exception e) { micState.setText("🎤 음성 인식 시작 실패: " + e.getMessage()); }
    }

    /** "응/아니" 판별: 부정 표현을 먼저 확인한다 ("안 해줘"에 "해줘"가 들어 있으므로). */
    void handleAnswer(String text) {
        if (pendingYes == null || text == null) return;
        String t = text.replace(" ", "");
        String[] no = {"아니", "괜찮", "됐어", "싫어", "그대로", "하지마", "안해", "놔둬", "필요없", "노"};
        String[] yes = {"응", "어", "네", "예", "그래", "좋아", "해줘", "해주", "오케이", "그렇게", "낮춰", "안내", "부탁", "콜"};
        for (String k : no) if (t.contains(k)) { answer(false, "음성 \"" + text + "\""); return; }
        for (String k : yes) if (t.contains(k)) { answer(true, "음성 \"" + text + "\""); return; }
        micState.setText("🎤 \"" + text + "\" — 다시 한번 \"응\" 또는 \"아니\"로 답해 주세요");
        listening = false;
        speak("다시 한번 응 또는 아니로 답해 주세요.");
        listenSoon("다시 한번 응 또는 아니로 답해 주세요.");
    }

    void answer(boolean yes, String how) {
        Runnable y = pendingYes, n = pendingNo;
        pendingYes = null; pendingNo = null; listenAfterSpeak = false; listening = false;
        try { if (sttReady) stt.stop(); } catch (Exception ignored) {}
        overlay.setVisibility(View.GONE);
        addLog(minute + "분 · 답변(" + how + "): " + (yes ? "예" : "아니오"));
        if (yes && y != null) y.run();
        if (!yes && n != null) n.run();
    }

    void acceptRest() {
        overlay.setVisibility(View.GONE);
        String km = String.valueOf((int) Math.round(restAreaKm));
        statusLine.setText("경로 변경: " + km + "km 앞 휴게소를 경유지로 추가했어요");
        statusLine.setTextColor(GOOD);
        addLog(minute + "분 · 운전자 '휴게소 안내' 선택 → 경유지 추가");
        speak("좋아요. " + km + "킬로미터 앞 휴게소로 안내할게요.");
        handler.postDelayed(() -> ask("휴게소까지 상쾌하게 갈까요?",
                "에어컨을 켜고 20°C로 낮춘 뒤,\n시트·핸들 열선은 끄고 시트 통풍을 켤게요.",
                "휴게소까지 상쾌하게 가도록 에어컨을 켜고 열선은 끌까요?",
                "그렇게 해줘", "그대로 둘게",
                () -> { addLog(minute + "분 · 운전자 동의 → 실내 환경 변경"); refreshCabin(); },
                () -> addLog(minute + "분 · 운전자 거절 → 변경 없음")), 3500);
        running = false;
        handler.removeCallbacks(tick);
    }

    void snooze() {
        overlay.setVisibility(View.GONE);
        alertShown = false;
        snoozeUntil = minute + 15;
        addLog(minute + "분 · 운전자 '괜찮아요' → 15분 뒤 다시 확인");
        speak("알겠어요. 15분 뒤에 다시 확인할게요. 무리하지 마세요.");
    }

    // ================= 차량 제어 (Pleos Vehicle SDK) =================
    void refreshCabin() {
        if (!vehicleReady) { addLog("차량 제어 불가 (연결 안 됨)"); return; }
        try {
            vehicle.getHVAC().setHvacAc(acArea != null ? acArea : driver, true,
                    (a, v) -> ok("에어컨 켜짐"), e -> fail("에어컨", e));
            vehicle.getHVAC().setHvacTemperature(driver, 20f, TemperatureUnit.CELSIUS,
                    (a, v) -> ok("운전석 온도 " + v + "°C"), e -> fail("온도", e));
            vehicle.getSeat().setHvacSeatTemperature(driver, 0,
                    (a, v) -> ok("시트 열선 끔"), e -> fail("시트 열선", e));
            vehicle.getSeat().setHvacSeatVentilation(driver, 2,
                    (a, v) -> ok("시트 통풍 2단계"), e -> fail("시트 통풍", e));
            vehicle.getSteeringWheel().setHvacSteeringWheelHeat(0,
                    v -> ok("핸들 열선 끔"), e -> fail("핸들 열선", e));
            // 등받이는 주행 중에 움직이지 않는다 (정차·휴게소 도착 시에만 제안)
        } catch (Exception e) {
            addLog("차량 제어 오류: " + e.getMessage());
        }
    }

    void probeHvacAreas() {
        try {
            vehicle.getHVAC().checkHvacAcCapabilities(
                    (java.util.List<SeatArea> areas) -> {
                        if (areas != null && !areas.isEmpty()) acArea = areas.get(0);
                        return Unit.INSTANCE;
                    }, e -> fail("에어컨 지원 확인", e));
            vehicle.getHVAC().checkHvacPowerCapabilities(
                    (java.util.List<SeatArea> areas) -> {
                        if (areas != null && !areas.isEmpty()) powerArea = areas.get(0);
                        return Unit.INSTANCE;
                    }, e -> fail("공조 전원 확인", e));
            vehicle.getHVAC().checkHvacFanSpeedCapabilities(
                    (java.util.Map<SeatArea, ?> m) -> {
                        if (m != null && !m.isEmpty()) fanArea = m.keySet().iterator().next();
                        return Unit.INSTANCE;
                    }, e -> fail("풍량 지원 확인", e));
        } catch (Exception e) { addLog("공조 확인 오류: " + e.getMessage()); }
    }

    void carFan(int level) {
        if (!vehicleReady) return;
        try {
            SeatArea fa = fanArea != null ? fanArea : driver;
            // 공조 전원이 꺼져 있으면 풍량을 바꿀 수 없으므로 전원을 먼저 켠다
            vehicle.getHVAC().setHvacPower(powerArea != null ? powerArea : fa, true, (a, v) -> {
                handler.postDelayed(() -> vehicle.getHVAC().setHvacFanSpeed(fa, level,
                        (a2, v2) -> ok("풍량 " + v2 + "단계"), e -> fail("풍량", e)), 400);
                return Unit.INSTANCE;
            }, e -> fail("공조 전원", e));
        } catch (Exception e) { addLog("차량 제어 오류: " + e.getMessage()); }
    }

    Unit ok(String what) { runOnUiThread(() -> addLog("차량 ✓ " + what)); return Unit.INSTANCE; }
    Unit fail(String what, Exception e) {
        runOnUiThread(() -> addLog("차량 ✗ " + what + " (" + (e == null ? "" : e.getClass().getSimpleName() + ": " + e.getMessage()) + ")"));
        return Unit.INSTANCE;
    }

    void updateLinkState(String error) {
        if (ttsState == null) return;
        String t = "차량 SDK: " + (vehicleReady ? "연결됨" : "대기") + "  ·  음성 안내: " + (ttsReady ? "연결됨" : "대기") + "  ·  음성 인식: " + (sttReady ? "연결됨" : "대기");
        ttsState.setText(error == null ? t : t + "\n" + error);
    }

    void speak(String text) {
        if (ttsReady) { tts.stop(); tts.speak(text, true); }
    }

    void addLog(String line) {
        log.insert(0, "• " + line + "\n");
        if (logView != null) logView.setText(log.toString());
    }

    static String fmtDuration(int m) {
        int h = m / 60, mm = m % 60;
        if (h == 0) return mm + "분";
        return mm == 0 ? h + "시간" : h + "시간 " + mm + "분";
    }

    // ================= 화면 갱신 =================
    void render() {
        hrValue.setText(String.valueOf((int) Math.round(hr)));
        hrvValue.setText(String.valueOf((int) Math.round(hrv)));
        sleepValue.setText("5h 12m");
        driveValue.setText((minute / 60) + "h " + (minute % 60) + "m");
        speedValue.setText(running || minute > 0 ? "100" : "0");
        restValue.setText((int) Math.round(restAreaKm) + "km");

        int score = fatigueScore();
        scoreValue.setText(String.valueOf(score));
        int color = score >= 75 ? BAD : score >= 50 ? WARN : GOOD;
        scoreValue.setTextColor(color);
        scoreLabel.setText(score >= 75 ? "위험" : score >= 50 ? "주의" : "양호");
        scoreLabel.setTextColor(color);
        scoreBar.setBackground(round(color, 12));
        int w = scoreTrack.getWidth();
        if (w > 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scoreBar.getLayoutParams();
            lp.width = Math.max(24, w * score / 100);
            scoreBar.setLayoutParams(lp);
        } else {
            scoreTrack.post(this::render);
        }
        if (!alertShown && statusLine.getCurrentTextColor() != GOOD) {
            statusLine.setText(running ? "주행 중 · 워치 신호 분석 중" : "대기 중");
            statusLine.setTextColor(QUIET);
        }
    }

    // ================= 화면 구성 =================
    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(64, 48, 64, 40);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // 상단 바
        LinearLayout top = row();
        TextView title = text("Mallo  ·  Driver Care Voice Assistant", 44, INK, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sim = text("워치 데이터: 시뮬레이션", 24, QUIET, false);
        sim.setPadding(24, 10, 24, 10);
        sim.setBackground(roundStroke(CARD, LINE, 40));
        top.addView(sim);
        page.addView(top);

        statusLine = text("대기 중", 28, QUIET, false);
        statusLine.setPadding(0, 12, 0, 28);
        page.addView(statusLine);

        // 주의 배너
        cautionBanner = row();
        cautionBanner.setPadding(32, 20, 32, 20);
        cautionBanner.setBackground(round(Color.parseColor("#3A2F14"), 20));
        cautionText = text("", 30, WARN, true);
        cautionBanner.addView(cautionText);
        cautionBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(-1, -2);
        cbl.bottomMargin = 24;
        page.addView(cautionBanner, cbl);

        // 본문: 왼쪽 지표 / 가운데 피로도 / 오른쪽 기록
        LinearLayout body = row();
        body.setGravity(Gravity.TOP);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // 왼쪽: 워치 + 주행 지표 (2열 x 3행)
        LinearLayout left = col();
        body.addView(left, weight(1.25f, 0, 24));
        LinearLayout r1 = row(), r2 = row(), r3 = row();
        left.addView(r1, rowLp()); left.addView(r2, rowLp()); left.addView(r3, rowLp());
        hrValue = metric(r1, "심박 (워치)", "bpm", 0);
        hrvValue = metric(r1, "심박변이도 (워치)", "ms", 24);
        sleepValue = metric(r2, "어젯밤 수면 (워치)", "", 0);
        driveValue = metric(r2, "연속 주행", "", 24);
        speedValue = metric(r3, "속도 (차량)", "km/h", 0);
        restValue = metric(r3, "다음 휴게소 (내비)", "", 24);

        // 가운데: 피로도
        LinearLayout mid = col();
        mid.setPadding(48, 40, 48, 40);
        mid.setBackground(round(CARD, 28));
        body.addView(mid, weight(1f, 0, 24));
        mid.addView(text("피로도 점수", 30, QUIET, false));
        LinearLayout sv = row();
        sv.setGravity(Gravity.BOTTOM);
        scoreValue = text("0", 180, GOOD, true);
        sv.addView(scoreValue);
        scoreLabel = text("양호", 48, GOOD, true);
        scoreLabel.setPadding(24, 0, 0, 40);
        sv.addView(scoreLabel);
        mid.addView(sv);
        scoreTrack = new FrameLayout(this);
        scoreTrack.setBackground(round(LINE, 12));
        scoreBar = new View(this);
        scoreTrack.addView(scoreBar, new FrameLayout.LayoutParams(24, -1));
        mid.addView(scoreTrack, new LinearLayout.LayoutParams(-1, 24));
        TextView legend = text("0–49 양호 · 50–74 주의 · 75 이상 경고", 24, QUIET, false);
        legend.setPadding(0, 16, 0, 32);
        mid.addView(legend);
        mid.addView(text("수면 부족 + 연속 주행 시간 + 출발 대비 심박 하락을 합산", 24, QUIET, false));
        View spacer = new View(this);
        mid.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        ttsState = text("SDK 연결 확인 중", 24, QUIET, false);
        mid.addView(ttsState);

        // 오른쪽: 기록 + 데모 버튼
        LinearLayout right = col();
        right.setPadding(40, 40, 40, 40);
        right.setBackground(round(CARD, 28));
        body.addView(right, weight(0.85f, 0, 0));
        right.addView(text("안내 기록", 30, QUIET, false));
        logView = text("", 26, INK, false);
        logView.setLineSpacing(10, 1f);
        logView.setPadding(0, 20, 0, 0);
        right.addView(logView, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout ctl = row();
        Button fast = button("빠르게 ×4", CARD, INK);
        fast.setOnClickListener(v -> { tickMs = tickMs == 1000 ? 250 : 1000; fast.setText(tickMs == 250 ? "보통 속도" : "빠르게 ×4"); });
        Button again = button("처음부터", CARD, INK);
        again.setOnClickListener(v -> reset());
        ctl.addView(fast, weight(1, 0, 16));
        ctl.addView(again, weight(1, 0, 0));
        right.addView(ctl);

        // 경고 오버레이
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC0B0D11"));
        overlay.setClickable(true);
        LinearLayout box = col();
        box.setPadding(80, 64, 80, 56);
        box.setBackground(roundStroke(Color.parseColor("#2A1416"), BAD, 36));
        overlayIcon = text("⚠", 96, BAD, true);
        box.addView(overlayIcon);
        overlayTitle = text("", 64, INK, true);
        overlayTitle.setPadding(0, 8, 0, 24);
        box.addView(overlayTitle);
        overlayBody = text("", 36, INK, false);
        overlayBody.setLineSpacing(14, 1f);
        box.addView(overlayBody);
        LinearLayout btns = row();
        btns.setPadding(0, 48, 0, 0);
        yesBtn = button("휴게소 안내", BAD, Color.WHITE);
        noBtn = button("괜찮아요", CARD, INK);
        btns.addView(yesBtn, weight(1.4f, 0, 24));
        btns.addView(noBtn, weight(1, 0, 0));
        box.addView(btns);
        micState = text("", 28, ACCENT, false);
        micState.setPadding(0, 28, 0, 0);
        box.addView(micState);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(1300, -2, Gravity.CENTER);
        overlay.addView(box, blp);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    TextView metric(LinearLayout parent, String label, String unit, int leftMargin) {
        LinearLayout c = col();
        c.setPadding(40, 32, 40, 32);
        c.setBackground(round(CARD, 28));
        c.addView(text(label, 26, QUIET, false));
        LinearLayout v = row();
        v.setGravity(Gravity.BOTTOM);
        TextView value = text("-", 64, INK, true);
        value.setSingleLine(true);
        v.addView(value);
        if (!unit.isEmpty()) {
            TextView u = text(unit, 28, QUIET, false);
            u.setPadding(12, 0, 0, 14);
            v.addView(u);
        }
        c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.leftMargin = leftMargin;
        parent.addView(c, lp);
        return value;
    }

    LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.bottomMargin = 24;
        return lp;
    }

    LinearLayout.LayoutParams weight(float w, int h, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h == 0 ? -1 : h, w);
        lp.rightMargin = rightMargin;
        return lp;
    }

    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp * 0.75f);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(30 * 0.75f);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundStroke(bg, bg == CARD ? LINE : bg, 24));
        b.setMinHeight(110);
        return b;
    }

    static GradientDrawable round(int color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(r);
        return d;
    }

    static GradientDrawable roundStroke(int color, int stroke, int r) {
        GradientDrawable d = round(color, r);
        d.setStroke(3, stroke);
        return d;
    }
}
