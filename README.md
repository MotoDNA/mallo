# Mallo – Driver Care Voice Assistant

**스마트워치 건강 신호와 차량 정보로 운전자의 피로를 알아채고, 먼저 말을 건 뒤 운전자가 말 한마디로 동의하면 차를 맞춰 주는 Pleos Connect용 운전자 케어 음성 비서입니다.**

현대자동차그룹 차량용 앱 개발 공모전(2026) 출품작 · 디엔에이랩스(DNALabs)

![주의 단계 질문](docs/screenshots/01_caution_question.png)

## 핵심 기능

| 기능 | 설명 |
| --- | --- |
| 피로도 계산 | 수면 부족 + 연속 주행 시간 + 출발 대비 심박 하락으로 0~100점 계산 (워치 데이터는 데모에서 시뮬레이션) |
| 주의 단계 (50~74점) | 운전석 창문을 살짝 열고(비·눈·미세먼지 나쁨일 땐 안 함) "실내 온도도 조금 낮춰 볼까요?" → 동의 시 24→23→22→21°C로 한 칸씩 |
| 경고 단계 (75점 이상) | "졸음 신호가 감지됐어요. 휴게소에서 쉬어 가시겠어요?" |
| 동의 후 실내 환경 변경 | 에어컨 켜기, 20°C, 시트·핸들 열선 끄기, 시트 통풍 2단계 |
| 음성으로 답하기 | "응", "아니", "그렇게 해줘", "괜찮아" 등 음성 대답 인식 (버튼도 가능) |

**동의 기반 제어 원칙**: 차량 기능을 바꾸기 전에 먼저 묻고, 운전자가 동의할 때만 실행합니다. 예외로 창문 살짝 열기는 안내만 하고 바로 실행하되, 비·눈이 오거나(와이퍼 작동) 미세먼지가 나쁘면 열지 않습니다. 주행 중에는 등받이 등 자세를 바꾸는 기능을 쓰지 않습니다.

## 사용한 Pleos SDK

| SDK | 버전 | 용도 |
| --- | --- | --- |
| Vehicle | 2.3.2 | 에어컨, 온도, 시트·핸들 열선, 시트 통풍, 공조 전원, 운전석 창문 위치, 와이퍼 상태(비·눈 판단) |
| TextToSpeech | 3.3.1 | 질문·안내 음성 (ON_DEVICE) |
| SpeechToText | 3.3.1 | 음성 대답 인식 (ON_DEVICE, ko-KR) |

## APK 위치

- `apk/DNALabs_Mallo_release.apk` (release 서명, versionName 0.1.0)

## 테스트 환경 및 필요한 설정

- Pleos Connect Emulator **v2.1.0** (Android 14 / API 34, `system-images;android-34;pleos_car_emulator;arm64-v8a`)
- 개발 환경: Android Studio, Gradle 8.14.3 (wrapper 포함), JDK 17 이상, minSdk 28, compileSdk 35
- 테스트 기기: macOS (Apple Silicon) 에뮬레이터
- **마이크**: 음성 대답을 쓰려면 에뮬레이터의 Extended Controls → Microphone → "Virtual microphone uses host audio input"을 켜야 합니다. 명령줄 실행 시 `-allow-host-audio` 옵션을 붙입니다.
- 앱 첫 실행 시 녹음 권한 허용이 필요합니다.

## 실행 방법

### APK로 바로 실행

```bash
adb install apk/DNALabs_Mallo_release.apk
adb shell pm grant ai.dnalabs.drivecare android.permission.RECORD_AUDIO
adb shell am start -n ai.dnalabs.drivecare/.MainActivity
```

### 소스에서 빌드

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pleos SDK는 `settings.gradle`에 등록된 Pleos 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받습니다.

### 데모 순서

1. 앱이 열리면 자동으로 주행 시뮬레이션이 시작됩니다 (1초 = 주행 1분).
2. 오른쪽 아래 **빠르게 ×4**로 시간을 빠르게 흘릴 수 있습니다.
3. 약 58분째 **주의 질문**, 약 87분째 **졸음 경고**가 나옵니다. 음성이 끝난 뒤 "응" 또는 "아니"라고 말하거나 버튼을 누릅니다.
4. **휴게소 안내** → **그렇게 해줘**를 고르면 오른쪽 안내 기록에 차량 제어 결과(✓)가 표시됩니다.
5. **처음부터** 버튼으로 다시 시작합니다.
6. 날씨 조건 시험: `adb shell am start -f 0x20000000 -n ai.dnalabs.drivecare/.MainActivity --es weather rain` (또는 `dust`, `clear`) 후 주의 단계가 오면 창문을 열지 않습니다. 미세먼지는 데모에서 시뮬레이션 값이며, 출시 버전은 에어코리아 공공 API를 연동합니다.

## 화면

| 창문 환기 + 온도 한 칸씩 | 휴게소 안내 후 질문 | 동의 후 차량 제어 결과 |
| --- | --- | --- |
| ![](docs/screenshots/04_window_and_temp_step.png) | ![](docs/screenshots/02_rest_area_question.png) | ![](docs/screenshots/03_vehicle_control_result.png) |

## 개발 예정

- NaviHelper SDK로 휴게소 경유지 추가, FusedLocation SDK로 실제 휴게소 거리 계산
- 정차(P단) 시에만 등받이 등 자세 변경 제안
- 갤럭시워치4 실시간 심박 연동 (Samsung Health Sensor SDK), Health Connect 기록 연동

## 안내

Mallo는 의료 진단 기기가 아니라 안전 운전을 돕는 안내 서비스입니다. 데모의 워치 데이터는 시뮬레이션 값입니다.
