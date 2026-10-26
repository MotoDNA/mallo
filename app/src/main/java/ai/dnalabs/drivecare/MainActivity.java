package ai.dnalabs.drivecare;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mallo – Driver Care Voice Assistant
 * 스마트워치 심박(실시간 또는 시뮬레이션) + 주행 정보로 피로도를 계산하고,
 * 먼저 말을 건 뒤 운전자가 동의하면 차량 환경을 바꾼다.
 *
 * - Pleos 차량/에뮬레이터: Pleos Vehicle·TTS·STT SDK 사용
 * - 갤럭시 탭 등 일반 기기: 가상 차량 + 기기 음성(TTS/음성 인식) 사용
 * - 심박: 블루투스 표준 심박 서비스(Mallo Watch 또는 심박 벨트), 연결 전에는 시뮬레이션
 */
public class MainActivity extends Activity implements Car.Listener, Voice.Listener, HeartLink.Listener {

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
    static final int HEART = Color.parseColor("#FF6B81");

    /** 화면 설계 기준 높이(px). 어떤 기기든 이 높이에 맞춰 전체를 확대/축소한다. */
    static final int DESIGN_H = 1210;

    // ---- 상태 ----
    final Handler handler = new Handler(Looper.getMainLooper());
    final Random rnd = new Random(7);
    final double[] sleepOptions = {5.2, 4.0, 6.5, 7.5};
    int sleepIdx = 0;
    double sleepHours = 5.2;          // 어젯밤 수면 (출시 버전은 Health Connect 수면 기록)
    double baselineHr = 74;           // 출발 시 심박 (실시간 연결 시 처음 30초 평균으로 다시 잡음)
    int minute = 0;                   // 연속 주행 시간(분)
    double hr = baselineHr;           // 피로도 계산에 쓰는 심박
    double hrv = 32;
    double restAreaKm = 23;
    int snoozeUntil = -1;
    boolean running = false, alertShown = false;
    int pm25 = 18;                    // 바깥 미세먼지(PM2.5). 출시 버전은 에어코리아 API — 지금은 시뮬레이션

    // 진행 속도: 시연(1초=1분), 빠르게(0.25초=1분), 실제 시간(1분=1분)
    int speedMode = 0;
    final int[] speedMs = {1000, 250, 60000};
    final String[] speedName = {"시연 속도", "빠르게 ×4", "실제 시간"};

    // 실시간 심박
    HeartLink heart;
    int liveBpm = 0;
    long liveAt = 0;
    double liveSmooth = 0;            // 피로도 계산용 부드럽게 만든 심박 (약 20초 평균)
    boolean linkUp = false;
    final List<Integer> calib = new ArrayList<>();
    boolean calibrated = false;
    boolean demoBoost = true;         // 실제 심박에 '졸음 패턴'을 더해 시연 (화면에 표시)

    Car car;
    Voice voice;
    Runnable pendingYes, pendingNo;
    boolean listenAfterSpeak = false, listening = false;

    // ---- 화면 요소 ----
    FrameLayout outer, root;
    TextView hrValue, hrLabel, hrvValue, sleepValue, driveValue, restValue, tempValue, tempLabel;
    LinearLayout tempCard;
    TextView scoreValue, scoreLabel, statusLine, linkState, logView, watchChip, carLine, micState;
    View scoreBar;
    FrameLayout scoreTrack, overlay;
    TextView overlayTitle, overlayBody, overlayIcon;
    LinearLayout cautionBanner;
    TextView cautionText;
    Button yesBtn, noBtn, speedBtn, boostBtn;
    final StringBuilder log = new StringBuilder();

    static boolean isPleos(Activity a) {
        try { a.getPackageManager().getPackageInfo("ai.pleos.playground.caas", 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        askPermissions();

        boolean pleos = isPleos(this);
        Car c = null;
        if (pleos) { try { c = new PleosCar(this); } catch (Throwable e) { addLog("Pleos 차량 연결 실패 → 가상 차량 사용"); } }
        car = c != null ? c : new VirtualCar();
        car.setListener(this);
        voice = pleos ? new PleosVoice(this) : new AndroidVoice(this);
        voice.init(this);
        heart = new HeartLink(this, this);

        carLine.setVisibility(car.isVirtual() ? View.VISIBLE : View.GONE);
        tempReset.run();
        onChanged();
        updateLinkState();
        render();
        handler.postDelayed(this::start, 1500);
        // 권한이 이미 있으면 바로 워치 찾기 시작
        handler.postDelayed(() -> { if (hasBtPermission() && !heart.isActive()) heart.start(); }, 2000);
    }

    // ================= 권한 =================
    void askPermissions() {
        List<String> need = new ArrayList<>();
        need.add(android.Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(android.Manifest.permission.BLUETOOTH_SCAN);
            need.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> ask = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 7);
    }

    boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (heart != null && hasBtPermission() && !heart.isActive()) heart.start();
        updateLinkState();
    }

    // 영상 제작·시험용 adb 명령
    //   am start -f 0x20000000 -n ai.dnalabs.drivecare/.MainActivity --es say "문장"
    //   --es weather rain|dust|clear   --ei bpm 72 (심박 수동 입력)
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String t = intent.getStringExtra("say");
        if (t != null) { running = false; handler.removeCallbacks(tick); voice.speak(t); }
        String w = intent.getStringExtra("weather");
        if (w != null) { car.setRaining(w.equals("rain")); pm25 = w.equals("dust") ? 80 : 18; addLog("시험: 날씨 = " + w); }
        int bpm = intent.getIntExtra("bpm", -1);
        if (bpm > 0) onHeart(bpm);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (heart != null) heart.stop();
        if (voice != null) voice.release();
        if (car != null) car.release();
        super.onDestroy();
    }

    // ================= 실시간 심박 (HeartLink.Listener) =================
    @Override public void onHeart(int bpm) {
        liveBpm = bpm;
        liveAt = System.currentTimeMillis();
        liveSmooth = liveSmooth <= 0 ? bpm : liveSmooth * 0.95 + bpm * 0.05;
        if (!calibrated) {
            calib.add(bpm);
            baselineHr = liveSmooth;        // 기준값이 잡히기 전에는 지금 심박을 기준으로 (점수가 튀지 않게)
            // 처음 20번은 버린다(차에 막 탔을 때는 걸어온 직후라 심박이 높음), 그다음 40번의 중간값을 기준으로
            if (calib.size() >= 60) {
                List<Integer> sorted = new ArrayList<>(calib.subList(20, calib.size()));
                java.util.Collections.sort(sorted);
                baselineHr = sorted.get(sorted.size() / 2);   // 중간값: 순간적으로 튀는 값에 덜 흔들림
                liveSmooth = baselineHr;
                calibrated = true;
                addLog("워치 심박 기준값 " + Math.round(baselineHr) + " bpm (안정된 뒤 40회 측정의 중간값)");
            }
        }
        boostBtn.setVisibility(View.VISIBLE);
        if (!running || speedMode == 2) render();
        else { hrValue.setText(String.valueOf(bpm)); }
    }

    @Override public void onLinkState(String text, boolean connected) {
        watchChip.setText(connected ? "❤  " + text : "⌚  " + text);
        watchChip.setTextColor(connected ? HEART : QUIET);
        watchChip.setBackground(roundStroke(CARD, connected ? HEART : LINE, 40));
        if (connected && !linkUp) addLog("워치 연결됨 · 실시간 심박 사용");
        linkUp = connected;
        render();
    }

    /** 워치 심박을 쓰는 중인지 (한 번이라도 받았고, 연결 중이거나 최근 30초 안에 받았으면) */
    boolean liveHr() { return liveBpm > 0 && (linkUp || System.currentTimeMillis() - liveAt < 30000); }
    /** 최근 15초 안에 새 값이 왔는지 */
    boolean liveFresh() { return liveBpm > 0 && System.currentTimeMillis() - liveAt < 15000; }

    // ================= 음성 (Voice.Listener) =================
    @Override public void onState() { updateLinkState(); }
    @Override public void onSpeechDone() { if (listenAfterSpeak) { listenAfterSpeak = false; startListening(); } }
    @Override public void onListenStart() { if (pendingYes != null) micState.setText("🎤 듣고 있어요… \"응\" 또는 \"아니\"라고 말해 주세요"); }
    @Override public void onPartial(String text) { micState.setText("🎤 \"" + text + "\""); }
    @Override public void onFinal(String text) { micState.setText("🎤 \"" + text + "\""); listening = false; handleAnswer(text); }
    @Override public void onListenError(String why) {
        listening = false;
        if (pendingYes != null) micState.setText("🎤 잘 못 들었어요. 버튼으로 답해 주세요 (" + why + ")");
    }

    void speak(String text) { voice.speak(text); }

    // ================= 차량 (Car.Listener) =================
    @Override public void onTemp(float v, boolean changed) { showTemp(v, changed); }
    @Override public void onLog(String line) { addLog(line); }
    @Override public void onChanged() { if (carLine != null && car != null) carLine.setText(car.summary()); }

    // ================= 시뮬레이션 =================
    void start() {
        if (running) return;
        running = true;
        addLog("주행 시작 · " + car.name() + " · " + voice.name());
        car.setPower(true);
        handler.postDelayed(() -> car.setTemp(24f, false), 400);
        speak("출발합니다. 어젯밤 수면이 " + sleepText(true) + (sleepHours < 6 ? "으로 짧았어요. " : "이에요. ")
                + "피곤하면 언제든 말씀해 주세요.");
        handler.post(tick);
    }

    void reset() {
        handler.removeCallbacks(tick);
        running = false; alertShown = false; snoozeUntil = -1;
        minute = 0; hrv = 32; restAreaKm = 23;
        if (!liveHr()) { baselineHr = 74; hr = baselineHr; }
        pendingYes = null; pendingNo = null; listenAfterSpeak = false;
        voice.stopListening();
        log.setLength(0);
        overlay.setVisibility(View.GONE);
        cautionBanner.setVisibility(View.GONE);
        statusLine.setTextColor(QUIET);
        render();
        handler.postDelayed(this::start, 500);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            // 질문에 대답을 기다리는 동안에는 시간을 멈춘다 (질문이 겹치지 않게)
            if (pendingYes != null) { if (running) handler.postDelayed(this, Math.min(1000, speedMs[speedMode])); return; }
            minute++;
            // 졸음 패턴(데모용 단순 모델): 30분 이후 심박은 서서히 떨어지고 HRV는 올라간다
            double drowsy = Math.min(1.0, Math.max(0, (minute - 30) / 70.0));
            if (liveHr()) {
                hr = liveSmooth - (demoBoost ? 13 * drowsy : 0);
            } else {
                hr = baselineHr - 13 * drowsy + rnd.nextGaussian() * 1.2;
            }
            hrv = 32 + 18 * drowsy + rnd.nextGaussian() * 1.5;
            if (!alertShown) restAreaKm -= 100 / 60.0; // 시속 100km
            if (restAreaKm < 1) restAreaKm = 24 + rnd.nextInt(6);
            render();

            int score = fatigueScore();
            boolean snoozed = minute < snoozeUntil;
            if (score >= 75 && !alertShown && !snoozed) {
                showAlert(score);
            } else if (score >= 50 && score < 75 && cautionBanner.getVisibility() != View.VISIBLE && !alertShown) {
                caution(score);
            }
            if (running) handler.postDelayed(this, speedMs[speedMode]);
        }
    };

    void caution(int score) {
        cautionBanner.setVisibility(View.VISIBLE);
        addLog(minute + "분 · 주의 단계 (피로도 " + score + ")");
        // 창문 살짝 열기는 묻지 않고 바로 한다. 단, 비·눈(와이퍼 작동) 또는 미세먼지 나쁨이면 열지 않는다.
        boolean raining = car.isRaining();
        boolean windowOk = !raining && pm25 <= 35;
        String why = raining ? "비·눈이 와서" : "미세먼지가 나빠서";
        if (windowOk) {
            addLog(minute + "분 · 환기: 창문 살짝 열기 (묻지 않는 예외 — 비·눈·미세먼지 나쁨일 땐 안 함)");
            car.openWindowSlightly();
            cautionText.setText("환기: 운전석 창문을 살짝 열었어요 (비·눈 없음 · 미세먼지 " + pm25 + "㎍/㎥ 좋음)");
        } else {
            cautionText.setText("환기: " + why + " 창문은 열지 않았어요");
            addLog(minute + "분 · " + why + " 창문 열지 않음");
        }
        ask("피로 신호가 조금씩 보여요",
                (windowOk ? "환기되도록 창문을 살짝 열었어요.\n" : why + " 창문은 열지 않을게요.\n")
                        + "실내 온도를 21°C로 조금 낮춰 볼까요?",
                "피로 신호가 조금씩 보여요. "
                        + (windowOk ? "환기되도록 창문을 살짝 열게요. " : why + " 창문은 열지 않을게요. ")
                        + "실내 온도도 조금 낮춰 볼까요?",
                "낮춰 줘", "괜찮아요",
                () -> { addLog(minute + "분 · 운전자 동의 → 온도 21°C"); stepTemp(24, 21); },
                () -> addLog(minute + "분 · 운전자 거절 → 변경 없음"));
    }

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
        micState.setText(voice.canListen() ? "🎤 질문이 끝나면 말로 답할 수 있어요" : "");
        overlayTitle.setText("졸음 신호가 감지됐어요");
        overlayBody.setText("심박이 출발 때보다 " + drop + " bpm 낮아졌고,\n"
                + driveText + "째 쉬지 않고 운전 중이에요.\n"
                + km + "km 앞 휴게소에서 쉬어 가시겠어요?");
        overlay.setVisibility(View.VISIBLE);
        addLog(minute + "분 · 경고 (피로도 " + score + ", 심박 -" + drop + ")");
        String v = "졸음 신호가 감지됐어요. " + driveText + "째 쉬지 않고 운전 중이에요. "
                + km + "킬로미터 앞 휴게소에서 쉬어 가시겠어요?";
        speak(v);
        listenSoon(v);
    }

    /** 운전자에게 먼저 묻고, 동의할 때만 실행한다. */
    void ask(String title, String body, String voiceText, String yes, String no, Runnable onYes, Runnable onNo) {
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
        micState.setText(voice.canListen() ? "🎤 질문이 끝나면 말로 답할 수 있어요" : "");
        overlay.setVisibility(View.VISIBLE);
        speak(voiceText);
        listenSoon(voice.canSpeak() ? voiceText : "");
    }

    /** 안내 음성이 끝나면 듣기 시작. 음성 종료 신호가 안 오는 경우를 대비해 글자 수로 추정한 시간에도 시작. */
    void listenSoon(String spoken) {
        listening = false;
        listenAfterSpeak = voice.canListen();
        long wait = 600 + (long) (spoken.length() * 170L);
        handler.postDelayed(() -> { if (listenAfterSpeak) { listenAfterSpeak = false; startListening(); } }, wait);
    }

    void startListening() {
        if (!voice.canListen() || pendingYes == null || listening) return;
        listening = true;
        voice.listen();
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
        String again = "다시 한번 응 또는 아니로 답해 주세요.";
        speak(again);
        listenSoon(again);
    }

    void answer(boolean yes, String how) {
        Runnable y = pendingYes, n = pendingNo;
        pendingYes = null; pendingNo = null; listenAfterSpeak = false; listening = false;
        voice.stopListening();
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

    // ================= 차량 제어 =================
    void refreshCabin() {
        car.setAc(true);
        stepTemp(21, 20);
        car.seatHeat(0);
        car.seatVent(2);
        car.wheelHeat(0);
        // 등받이는 주행 중에 움직이지 않는다 (정차·휴게소 도착 시에만 제안)
    }

    /** 온도를 1°C씩 차례로 바꾼다 (24 → 23 → 22 → 21). 차량에 실제로 한 단계씩 설정한다. */
    void stepTemp(int from, int to) {
        int dir = to < from ? -1 : 1;
        int n = Math.abs(to - from);
        for (int i = 1; i <= n; i++) {
            final float t = from + dir * i;
            handler.postDelayed(() -> car.setTemp(t, true), 900L * i);
        }
    }

    void showTemp(float v, boolean changed) {
        tempValue.setText(String.valueOf(Math.round(v)));
        if (changed) {
            tempCard.setBackground(roundStroke(Color.parseColor("#12233F"), ACCENT, 28));
            tempValue.setTextColor(ACCENT);
            tempLabel.setText(tempName() + " · 방금 변경됨");
            handler.removeCallbacks(tempReset);
            handler.postDelayed(tempReset, 6000);
        }
    }

    String tempName() { return "운전석 온도 (" + (car != null && car.isVirtual() ? "가상 차량" : "차량") + ")"; }

    final Runnable tempReset = () -> {
        tempCard.setBackground(round(CARD, 28));
        tempValue.setTextColor(INK);
        tempLabel.setText(tempName());
    };

    void updateLinkState() {
        if (linkState == null || voice == null || car == null) return;
        linkState.setText(car.name() + "  ·  음성 안내: " + (voice.canSpeak() ? "연결됨" : "대기")
                + "  ·  음성 인식: " + (voice.canListen() ? "연결됨" : "대기"));
    }

    void addLog(String line) {
        log.insert(0, "• " + line + "\n");
        if (log.length() > 4000) log.setLength(4000);
        if (logView != null) logView.setText(log.toString());
    }

    static String fmtDuration(int m) {
        int h = m / 60, mm = m % 60;
        if (h == 0) return mm + "분";
        return mm == 0 ? h + "시간" : h + "시간 " + mm + "분";
    }

    String sleepText(boolean spoken) {
        int h = (int) sleepHours, m = (int) Math.round((sleepHours - h) * 60);
        if (spoken) return h + "시간" + (m > 0 ? " " + m + "분" : "");
        return h + "h " + m + "m";
    }

    // ================= 화면 갱신 =================
    void render() {
        boolean live = liveHr();
        hrValue.setText(String.valueOf(live ? liveBpm : (int) Math.round(hr)));
        hrLabel.setText(!live ? "심박 (시뮬레이션)"
                : !liveFresh() ? "심박 (워치 · 신호 기다리는 중)"
                : demoBoost ? "심박 (워치 실시간 · 시연 보정)" : "심박 (워치 실시간)");
        hrLabel.setTextColor(live ? HEART : QUIET);
        hrvValue.setText(String.valueOf((int) Math.round(hrv)));
        sleepValue.setText(sleepText(false));
        driveValue.setText((minute / 60) + "h " + (minute % 60) + "m");
        if (tempValue.getText().length() == 0 || "-".contentEquals(tempValue.getText())) tempValue.setText("--");
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
            statusLine.setText(running ? "주행 중 · 워치 신호 분석 중 · " + speedName[speedMode] : "대기 중");
            statusLine.setTextColor(QUIET);
        }
    }

    // ================= 화면 구성 =================
    void buildUi() {
        // outer: 실제 화면 크기. root: 설계 크기(높이 1210px)로 만든 뒤 화면에 맞게 확대/축소한다.
        outer = new FrameLayout(this);
        outer.setBackgroundColor(BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        outer.addView(root, new FrameLayout.LayoutParams(1680, DESIGN_H));
        outer.addOnLayoutChangeListener((v, l, t, r, btm, ol, ot, or, ob) -> {
            int W = r - l, H = btm - t;
            if (W <= 0 || H <= 0) return;
            float s = H / (float) DESIGN_H;
            int dw = Math.round(W / s);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
            if (lp.width != dw || root.getScaleX() != s) {
                lp.width = dw; lp.height = DESIGN_H;
                root.setLayoutParams(lp);
                root.setPivotX(0); root.setPivotY(0);
                root.setScaleX(s); root.setScaleY(s);
            }
        });

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(48, 36, 48, 32);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // 상단 바
        LinearLayout top = row();
        TextView title = text("Mallo  ·  Driver Care Voice Assistant", 44, INK, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        watchChip = text("⌚  워치 연결 준비 중", 24, QUIET, false);
        watchChip.setPadding(24, 10, 24, 10);
        watchChip.setBackground(roundStroke(CARD, LINE, 40));
        watchChip.setOnClickListener(v -> {
            if (!hasBtPermission()) { askPermissions(); return; }
            if (heart.isActive()) { heart.stop(); liveBpm = 0; liveSmooth = 0; linkUp = false; calibrated = false; calib.clear(); baselineHr = 74; render(); }
            else heart.start();
        });
        top.addView(watchChip);
        page.addView(top);

        statusLine = text("대기 중", 28, QUIET, false);
        statusLine.setPadding(0, 10, 0, 14);
        page.addView(statusLine);

        // 가상 차량 상태 줄 (차량이 없는 기기에서만 보임)
        carLine = text("", 26, INK, false);
        carLine.setPadding(28, 14, 28, 14);
        carLine.setBackground(round(Color.parseColor("#14202E"), 20));
        LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(-1, -2);
        cll.bottomMargin = 16;
        page.addView(carLine, cll);

        // 주의 배너
        cautionBanner = row();
        cautionBanner.setPadding(32, 16, 32, 16);
        cautionBanner.setBackground(round(Color.parseColor("#3A2F14"), 20));
        cautionText = text("", 30, WARN, true);
        cautionBanner.addView(cautionText);
        cautionBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(-1, -2);
        cbl.bottomMargin = 16;
        page.addView(cautionBanner, cbl);

        // 본문: 왼쪽 지표 / 가운데 피로도 / 오른쪽 기록
        LinearLayout body = row();
        body.setGravity(Gravity.TOP);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout left = col();
        body.addView(left, weight(1.25f, 0, 24));
        LinearLayout r1 = row(), r2 = row(), r3 = row();
        left.addView(r1, rowLp()); left.addView(r2, rowLp()); left.addView(r3, rowLp());
        hrValue = metric(r1, "심박 (시뮬레이션)", "bpm", 0);
        hrLabel = (TextView) ((LinearLayout) hrValue.getParent().getParent()).getChildAt(0);
        hrvValue = metric(r1, "심박변이도 (시뮬레이션)", "ms", 24);
        sleepValue = metric(r2, "어젯밤 수면 (눌러서 변경)", "", 0);
        ((View) sleepValue.getParent().getParent()).setOnClickListener(v -> {
            sleepIdx = (sleepIdx + 1) % sleepOptions.length;
            sleepHours = sleepOptions[sleepIdx];
            addLog("어젯밤 수면 " + sleepText(false) + "으로 설정");
            render();
        });
        driveValue = metric(r2, "연속 주행", "", 24);
        tempValue = metric(r3, "운전석 온도", "°C", 0);
        tempCard = (LinearLayout) tempValue.getParent().getParent();
        tempLabel = (TextView) tempCard.getChildAt(0);
        restValue = metric(r3, "다음 휴게소 (내비)", "", 24);

        LinearLayout mid = col();
        mid.setPadding(44, 36, 44, 36);
        mid.setBackground(round(CARD, 28));
        body.addView(mid, weight(1f, 0, 24));
        mid.addView(text("피로도 점수", 30, QUIET, false));
        LinearLayout sv = row();
        sv.setGravity(Gravity.BOTTOM);
        scoreValue = text("0", 170, GOOD, true);
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
        legend.setPadding(0, 16, 0, 24);
        mid.addView(legend);
        mid.addView(text("수면 부족 + 연속 주행 시간 + 출발 대비 심박 하락을 합산", 24, QUIET, false));
        mid.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        linkState = text("연결 확인 중", 24, QUIET, false);
        mid.addView(linkState);

        LinearLayout right = col();
        right.setPadding(36, 36, 36, 36);
        right.setBackground(round(CARD, 28));
        body.addView(right, weight(0.85f, 0, 0));
        right.addView(text("안내 기록", 30, QUIET, false));
        logView = text("", 24, INK, false);
        logView.setLineSpacing(8, 1f);
        logView.setPadding(0, 16, 0, 0);
        right.addView(logView, new LinearLayout.LayoutParams(-1, 0, 1));
        boostBtn = button("시연 보정 켜짐", CARD, INK);
        boostBtn.setVisibility(View.GONE);
        boostBtn.setOnClickListener(v -> {
            demoBoost = !demoBoost;
            boostBtn.setText(demoBoost ? "시연 보정 켜짐" : "시연 보정 꺼짐");
            addLog(demoBoost ? "시연 보정 켜짐: 실제 심박에 졸음 패턴(최대 -13bpm)을 더함" : "시연 보정 꺼짐: 실제 심박만 사용");
            render();
        });
        LinearLayout.LayoutParams bbl = new LinearLayout.LayoutParams(-1, -2);
        bbl.bottomMargin = 16;
        right.addView(boostBtn, bbl);
        LinearLayout ctl = row();
        speedBtn = button(speedName[0], CARD, INK);
        speedBtn.setOnClickListener(v -> {
            speedMode = (speedMode + 1) % speedMs.length;
            speedBtn.setText(speedName[speedMode]);
            addLog("진행 속도: " + speedName[speedMode] + (speedMode == 2 ? " (1분 = 주행 1분)" : ""));
            if (running) { handler.removeCallbacks(tick); handler.postDelayed(tick, Math.min(1000, speedMs[speedMode])); }
            render();
        });
        Button again = button("처음부터", CARD, INK);
        again.setOnClickListener(v -> reset());
        ctl.addView(speedBtn, weight(1, 0, 16));
        ctl.addView(again, weight(1, 0, 0));
        right.addView(ctl);

        // 질문·경고 오버레이
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC0B0D11"));
        overlay.setClickable(true);
        LinearLayout box = col();
        box.setPadding(80, 60, 80, 52);
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
        btns.setPadding(0, 44, 0, 0);
        yesBtn = button("휴게소 안내", BAD, Color.WHITE);
        noBtn = button("괜찮아요", CARD, INK);
        btns.addView(yesBtn, weight(1.4f, 0, 24));
        btns.addView(noBtn, weight(1, 0, 0));
        box.addView(btns);
        micState = text("", 28, ACCENT, false);
        micState.setPadding(0, 28, 0, 0);
        box.addView(micState);
        overlay.addView(box, new FrameLayout.LayoutParams(1300, -2, Gravity.CENTER));
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(outer);
    }

    TextView metric(LinearLayout parent, String label, String unit, int leftMargin) {
        LinearLayout c = col();
        c.setPadding(36, 28, 36, 28);
        c.setBackground(round(CARD, 28));
        c.addView(text(label, 25, QUIET, false));
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
        lp.bottomMargin = 20;
        return lp;
    }

    LinearLayout.LayoutParams weight(float w, int h, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h == 0 ? -1 : h, w);
        lp.rightMargin = rightMargin;
        return lp;
    }

    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    /** 글자 크기는 화면 밀도와 상관없이 설계 픽셀 기준 (root 확대/축소로 화면에 맞춤) */
    TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.75f);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * 0.75f);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundStroke(bg, bg == CARD ? LINE : bg, 24));
        b.setMinHeight(100);
        b.setMinimumHeight(100);
        b.setPadding(16, 8, 16, 8);
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
