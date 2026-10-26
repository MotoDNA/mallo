package ai.dnalabs.drivecare;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.listener.ResultListener;
import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.ErrorType;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.listener.EventListener;
import ai.pleos.playground.tts.listener.OnInitializeListener;

/** Pleos TextToSpeech / SpeechToText SDK (기기 내 합성·인식). */
class PleosVoice implements Voice {
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private SpeechToText stt;
    private boolean ttsReady, sttReady;
    private Listener l;

    PleosVoice(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    @Override public String name() { return "Pleos 음성"; }

    @Override public void init(Listener listener) {
        l = listener;
        tts = new TextToSpeech(ctx, Mode.ON_DEVICE);
        tts.initialize(new OnInitializeListener() {
            @Override public void onSuccess() { tts.setLocale(Locale.KOREA); ttsReady = true; h.post(l::onState); }
            @Override public void onFailure(ErrorType e) { h.post(l::onState); }
        });
        tts.addEventListener(new EventListener() {
            @Override public void onStart() {}
            @Override public void onReady() {}
            @Override public void onDone() { h.post(l::onSpeechDone); }
            @Override public void onError(ErrorType e) { h.post(l::onSpeechDone); }
            @Override public void onStop() {}
            @Override public void onUpdatedRms(double v) {}
        });
        stt = new SpeechToText(ctx, ai.pleos.playground.stt.constant.Mode.ON_DEVICE);
        stt.initialize(new ai.pleos.playground.stt.listener.OnInitializeListener() {
            @Override public void onSuccess() { stt.setLocale(Locale.KOREA); sttReady = true; h.post(l::onState); }
            @Override public void onFailure(ai.pleos.playground.stt.constant.ErrorType e) { h.post(l::onState); }
        });
        stt.addListener(new ResultListener() {
            @Override public void onUpdated(String text, boolean isFinal) { h.post(() -> { if (isFinal) l.onFinal(text); else l.onPartial(text); }); }
            @Override public void onUpdatedEpdData(long a, long b) {}
            @Override public void onStartedRecognition() { h.post(l::onListenStart); }
            @Override public void onEndedRecognition() {}
            @Override public void onError(ai.pleos.playground.stt.constant.ErrorType e) { h.post(() -> l.onListenError(String.valueOf(e))); }
            @Override public void onReady() {}
            @Override public void onUpdatedRms(double v) {}
        });
    }

    @Override public boolean canSpeak() { return ttsReady; }
    @Override public boolean canListen() { return sttReady; }

    @Override public void speak(String text) { if (ttsReady) { tts.stop(); tts.speak(text, true); } }
    @Override public void stopSpeaking() { if (ttsReady) tts.stop(); }

    @Override public void listen() {
        if (!sttReady) return;
        try { stt.request(); } catch (Exception e) { l.onListenError(e.getMessage()); }
    }
    @Override public void stopListening() { try { if (sttReady) stt.stop(); } catch (Exception ignored) {} }

    @Override public void release() {
        try { if (stt != null) stt.release(); } catch (Exception ignored) {}
        try { if (tts != null) tts.release(); } catch (Exception ignored) {}
    }
}
