package ai.dnalabs.drivecare;

/**
 * 차량 기능 묶음. Pleos 차량(에뮬레이터·실차)에서는 PleosCar, 갤럭시 탭처럼 차량이 없는 기기에서는 VirtualCar.
 * 결과는 Listener 로 알려 준다 (화면 갱신·안내 기록).
 */
interface Car {

    interface Listener {
        /** 운전석 온도가 바뀌었을 때 (changed=true 이면 화면에서 강조) */
        void onTemp(float celsius, boolean changed);
        /** 안내 기록에 남길 한 줄 */
        void onLog(String line);
        /** 가상 차량 상태가 바뀌었을 때 (가상 차량 화면 갱신용) */
        void onChanged();
    }

    String name();
    boolean isVirtual();
    void setListener(Listener l);

    void setPower(boolean on);
    void setTemp(float celsius, boolean highlight);
    void setAc(boolean on);
    void seatHeat(int level);
    void seatVent(int level);
    void wheelHeat(int level);
    void openWindowSlightly();

    /** 비·눈 여부 (와이퍼 작동 기준) */
    boolean isRaining();
    void setRaining(boolean raining);

    /** 가상 차량 화면에 보여 줄 한 줄 요약 (Pleos 차량은 빈 문자열) */
    String summary();

    void release();
}
