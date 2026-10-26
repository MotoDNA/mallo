package ai.dnalabs.drivecare;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/** 일반 안드로이드 기기(갤럭시 탭 등)의 기본 음성 합성 + 음성 인식. */
class AndroidVoice implements Voice {
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private SpeechRecognizer sr;
    private boolean ttsReady;
    private Listener l;
    private int seq = 0;

    AndroidVoice(Context ctx) { this.ctx = ctx; }

    @Override public String name() { return "기기 음성"; }

    @Override public void init(Listener listener) {
        l = listener;
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.KOREA);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setSpeechRate(1.05f);
            }
            h.post(l::onState);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { h.post(l::onSpeechDone); }
            @Override public void onError(String id) { h.post(l::onSpeechDone); }
        });
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { l.onListenStart(); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int code) { l.onListenError(errorText(code)); }
                @Override public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) l.onFinal(r.get(0)); else l.onListenError("인식 결과 없음");
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) l.onPartial(r.get(0));
                }
                @Override public void onEvent(int t, Bundle b) {}
            });
        }
        h.post(l::onState);
    }

    static String errorText(int c) {
        switch (c) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "못 알아들음";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "말소리 없음";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "마이크 권한 필요";
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "네트워크 오류";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "인식기 사용 중";
            default: return "오류 " + c;
        }
    }

    @Override public boolean canSpeak() { return ttsReady; }
    @Override public boolean canListen() { return sr != null; }

    @Override public void speak(String text) {
        if (!ttsReady) return;
        Bundle p = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "mallo" + (++seq));
    }
    @Override public void stopSpeaking() { if (ttsReady) tts.stop(); }

    @Override public void listen() {
        if (sr == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        try { sr.cancel(); sr.startListening(i); } catch (Exception e) { l.onListenError(e.getMessage()); }
    }
    @Override public void stopListening() { try { if (sr != null) sr.cancel(); } catch (Exception ignored) {} }

    @Override public void release() {
        try { if (sr != null) sr.destroy(); } catch (Exception ignored) {}
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
    }
}
