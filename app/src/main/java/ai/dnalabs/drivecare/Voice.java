package ai.dnalabs.drivecare;

/**
 * 음성 안내(TTS) + 음성 대답 인식(STT).
 * Pleos 차량에서는 PleosVoice, 일반 안드로이드 기기(갤럭시 탭)에서는 AndroidVoice.
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
interface Voice {

    interface Listener {
        void onState();                 // 연결 상태가 바뀜 (화면 표시 갱신)
        void onSpeechDone();            // 안내 음성이 끝남
        void onListenStart();
        void onPartial(String text);
        void onFinal(String text);
        void onListenError(String why);
    }

    String name();
    void init(Listener l);
    boolean canSpeak();
    boolean canListen();
    void speak(String text);
    void stopSpeaking();
    void listen();
    void stopListening();
    void release();
}
