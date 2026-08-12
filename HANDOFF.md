# android-workspace-manager — 작업 인계 문서

이 문서는 이전 세션에서 이 프로젝트를 작업한 에이전트가, 다음 에이전트에게 컨텍스트를 넘기기 위해 쓴 것입니다. **여기 적힌 "실측으로 확인된 사실"은 실제 기기에서 검증한 것이니 다시 조사하지 마세요.**

**2026-08-12 세션에서 8번(남은 작업)이 전부 구현·실기기 검증되었습니다.** 이제 이 앱은 기능적으로 완성 상태입니다. 최신 상태는 9번, 남은/알려진 이슈는 10번을 먼저 보세요.

---

## 1. 프로젝트가 무엇인가

Android 16의 freeform windowing을 이용해 폴더블/태블릿에서 **창 배치를 저장하고 복원하는 앱**입니다.

원본은 `Aypex/android-tiling-wm`(i3/Hyprland 스타일 실시간 타일링 WM 지향)을 fork한 것이고, 현재 repo는 `LuterGS/android-workspace-manager`입니다. **작업 도중 방향을 전환했습니다** — 아래 3번 참고.

- `origin` = `https://github.com/LuterGS/android-workspace-manager` (내 fork, public)
- `upstream` = `https://github.com/Aypex/android-tiling-wm` (원본)

---

## 2. 환경

### 기기
- **Galaxy Z Fold (SM-F968N), Android 16 (API 36), One UI**
- 내부 화면 논리 해상도 **1584x2160** (세로), density 320 (1dp = 2px)
- 커버 디스플레이는 displayId=1 (1918x822)
- freeform 관련 global 설정 3개는 **이미 활성화되어 있음** (`enable_freeform_support`, `force_resizable_activities`, `enable_non_resizable_multi_window`)
- Shizuku 설치·실행 중, 앱의 UserService는 `dev.atwm.tilingwm:tiling` 프로세스(shell UID)로 뜸

### 개발 머신 A: macOS (사용자의 평소 머신)
빌드 명령은 **반드시** 아래 형태로 쓰세요. 그냥 `gradle build`는 실패합니다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
ANDROID_HOME="$HOME/Library/Android/sdk" gradle assembleDebug
```

- **gradle wrapper가 없습니다** (`gradlew` 부재). 시스템 gradle 9.4.0 사용
- **`ANDROID_HOME`이 환경에 설정되어 있지 않습니다**. 매번 앞에 붙여야 함
- **JDK 21이 필요합니다.** homebrew 기본 JDK 26으로 빌드하면 AGP의 `JdkImageTransform`이 `jlink` 단계에서 실패
- 셸의 `grep`은 사용자 zsh alias로 **ugrep**입니다. 스크립트(`#!/usr/bin/env bash`)는 이 alias를 상속받지 못해 BSD grep을 집습니다. 명령줄에서 PCRE가 필요하면 `ggrep`을 명시적으로 쓰세요

### 개발 머신 B: Linux (OCI, aarch64) — 2026-08-12 세션에서 처음 사용
이 세션은 macOS가 아니라 별도의 Oracle Cloud aarch64 Linux VM(`opc` 유저)이었습니다. **JDK/Android SDK/Gradle이 전혀 없었지만, adb로 실기기(`10.8.0.2:38405`, 네트워크 경유)에는 이미 연결되어 있었습니다.** 즉 이 환경은 macOS 머신과 별개로 "코드 편집 + 기기 조작"이 가능한 세컨드 빌드 환경입니다.

**중요한 함정: Android SDK build-tools(`aidl`, `aapt2` 등)는 Linux에서 x86_64 바이너리만 배포됩니다.** aarch64 호스트에서 직접 실행하면 `cannot execute binary file`로 즉시 실패합니다. 해결책은 QEMU 유저모드 에뮬레이션:

```bash
# 1) 호스트 커널에 binfmt_misc로 x86_64 에뮬레이터 등록 (한 번만, 재부팅 시 소실될 수 있음)
docker run --privileged --rm tonistiigi/binfmt --install all

# 2) amd64 컨테이너를 하나 띄워서 그 안에서 빌드 (호스트 바이너리를 직접 실행하면
#    ld-linux-x86-64.so.2를 못 찾아 실패함 — 반드시 amd64 컨테이너 안이어야 함)
docker run -d --platform linux/amd64 --name android-build \
  -v /home/opc/development/android-workspace-manager:/workspace \
  -v /home/opc/android-sdk:/opt/android-sdk \
  -v /home/opc/gradle/gradle-9.4.0:/opt/gradle \
  -v /home/opc/.gradle-amd64:/gradle-home \
  -e ANDROID_HOME=/opt/android-sdk -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e GRADLE_USER_HOME=/gradle-home -w /workspace --user 1000:1000 \
  eclipse-temurin:21-jdk sleep infinity

docker exec android-build /opt/gradle/bin/gradle assembleDebug --console=plain
```

이 세션에서 만든 재사용 가능한 자산 (다음에 이 머신을 또 쓴다면 처음부터 다시 만들지 마세요):
- `/home/opc/android-sdk` — cmdline-tools + platform 35 + build-tools 35.0.0 + platform-tools
- `/home/opc/gradle/gradle-9.4.0` — Gradle 바이너리 (macOS와 동일 버전으로 맞춤)
- `/home/opc/.gradle-amd64` — 컨테이너 전용 GRADLE_USER_HOME (의존성 캐시, 788MB+)
- `android-build` 컨테이너 — 위 3개를 마운트한 상태로 떠 있음 (없으면 `docker run` 커맨드 재실행, 있으면 `docker start android-build`)
- 호스트 `adb`(`/usr/local/bin/adb`)는 QEMU 없이 네이티브로 잘 동작 — 설치/조작은 컨테이너 밖에서 `adb`로 직접 하면 됨

**첫 빌드는 24분 걸렸습니다** (의존성 다운로드 + QEMU 에뮬레이션 오버헤드 + Kotlin daemon 연결 실패로 인한 in-process fallback 컴파일). 캐시가 이미 있으니 다음 빌드는 훨씬 빠를 것으로 예상됩니다. Kotlin daemon 연결 실패(`Could not connect to Kotlin compile daemon`)는 매 빌드 재현될 수 있는데, **fallback으로 빌드 자체는 성공하니 무시해도 됩니다.**

---

## 3. 방향 전환 — 왜 실시간 타일링을 버렸는가

**초기 목표**: Hyprland처럼 창 사이 divider를 드래그하면 인접 창들이 실시간으로 함께 리사이즈.

**실측 결과 이 방향을 접었습니다:**

1. **Android에는 창이 경계를 공유한다는 개념이 없습니다.** 네이티브 resize handle로 한 창을 줄이면 옆 창은 그대로고 그 사이에 빈 구멍이 남습니다. (설정 창을 `528→616`으로 줄였더니 Chrome은 `528`에 그대로 있었음)
2. **One UI조차 드래그 중 실시간 리사이즈를 하지 않습니다.** logcat에 `FreeformResizeGuideWindow`가 찍히는데, 드래그 중에는 고스트 아웃라인만 그리고 손을 뗄 때 한 번에 커밋합니다.
3. **`resizeTask()`는 뒤에 있는 앱에 configuration change를 유발합니다.** 실제로 Chrome은 리사이즈 후 콘텐츠 리플로우를 따라오지 못했습니다. 매 프레임 호출하면 앱이 버벅입니다.

→ 그래서 **명시적 scene 저장/복원** 방식으로 전환했습니다. 사용자가 원할 때만 배치를 적용하므로 프레임 예산이라는 개념 자체가 없습니다.

divider 관련 코드(`Divider.kt`, `DividerOverlay.kt`, `resolveDrag`)는 **의도적으로 전부 삭제**했습니다. 되살리지 마세요.

---

## 4. 현재 아키텍처

```
MainActivity               Shizuku 연결 플로우 + 프리셋으로 새 scene 만들기 + scene 이름변경/삭제
      │                     (PresetBuilderDialog: 슬롯별 앱 선택 → 이름 지정 → 저장)
      ↓
FloatingWidget             드래그 가능한 puck + 확장 패널(저장/로드/롱프레스 삭제)
      ↓
TilingAccessibilityService   위젯 호스팅 + opt-in 자동 재배치. TYPE_ACCESSIBILITY_OVERLAY 사용
      ↓                       (덕분에 SYSTEM_ALERT_WINDOW 권한 불필요)
SceneManager              capture() / apply() + 200~1500ms 백오프 재시도 + currentTaskId()
                          apply()는 scene에 없는 다른 freeform 창을 먼저 진짜로 최소화함(minimizeOthers)
      ↓
SceneStore                SharedPreferences + JSON 영속화 (rename, nextAvailableName, autoRestoreEnabled)
      ↓
WindowTilingServiceImpl   Shizuku UserService (shell UID)
                          launchInFreeform / resizeTask / getVisibleTaskInfo
```

`engine/UsableArea.kt`의 `usableArea(context)`가 "scene의 기준 좌표계" 계산을 MainActivity/PresetBuilderDialog/TilingAccessibilityService가 공유하도록 뽑아낸 함수입니다 — 캡처와 적용이 서로 다른 사각형을 기준으로 삼으면 안 되므로. `WindowManager.getMaximumWindowMetrics()` + 실제 `WindowInsets`로 상태바/네비바를 계산합니다 (예전엔 `TilingConfig.statusBarHeight`/`navBarHeight`라는 고정 100px 추정치였다가, 회전 시 여백이 남는 버그로 이어져 실제 인셋 조회로 교체함 — 8번 참고).

### 핵심 설계 결정 (바꾸기 전에 이유를 읽으세요)

- **`SceneWindow`의 bounds는 픽셀이 아니라 usable area 대비 비율(0~1)입니다.** 이 기기는 접었다 펴면 화면이 `1584x2160` ↔ `1918x822`로 완전히 달라지고 회전하면 축이 바뀝니다. 픽셀로 저장하면 scene이 무용지물이 됩니다. 복원 시 1px 오차가 생기는데, 이건 그 대가로 받아들인 것입니다.
- **창 식별자는 패키지명 단독입니다.** taskId는 앱이 죽으면 무효라 못 씁니다. **"앱당 창 하나"를 가정**하기로 사용자와 합의했습니다 (Chrome 창 2개 같은 경우는 고려하지 않음).
- **`apply()`는 모든 앱을 먼저 한꺼번에 실행한 뒤** 재시도 루프로 회수합니다. 순차로 기다리면 콜드스타트가 직렬로 쌓입니다.
- **`Preset`은 `LayoutStrategy` + 슬롯 개수**이고, `toScene()`으로 Scene을 만듭니다. 즉 프리셋과 캡처된 배치는 downstream에서 같은 객체입니다. `PresetBuilderDialog`가 이 경로를 사용하는 유일한 곳입니다.
- **접근성 서비스는 기본적으로 창 이벤트에 반응하지 않지만, opt-in 자동 재배치가 하나 붙었습니다** (아래 참고). 기본값은 꺼짐(`SceneStore.autoRestoreEnabled = false`).
- **`apply()`는 새 scene을 배치하기 전에, 그 scene에 속하지 않은 다른 freeform 창을 전부 진짜로 최소화합니다** (`SceneManager.minimizeOthers()` → `IWindowTilingService.minimizeTask(taskId)`). 연속으로 다른 scene을 불러오면 이전 scene의 창이 새 scene 위/옆에 계속 남아있던 실사용 버그(사용자가 직접 발견) 때문에 추가했습니다. **최종 구현**: `WindowTilingServiceImpl`에서 이미 연결된 `atm`(`IActivityTaskManager`) 객체에 리플렉션으로 `getMultiTaskingBinder()`를 호출해 Samsung의 `com.samsung.android.multiwindow.IMultiTaskingBinder`를 얻고, 그 위에서 `minimizeTaskById(taskId)`를 호출합니다. 이건 네이티브 캡션바 `-` 버튼과 완전히 같은 경로입니다 (`adb logcat`으로 실제 버튼을 눌러 호출 스택을 직접 확인함: `ShellWindowDecoration.onClick(minimize_window)` → `IMultiTaskingBinder$Stub.onTransact` → `MultiTaskingBinder.minimizeTaskById` → `Task.moveTaskToBack`(내부용, AIDL의 것과 다름) + `Task.setMinimized`). `capture()`는 이제 `area`와 교차하지 않는 창도 걸러냅니다 — 최소화된 task가 여전히 오래된 좌표를 들고 `isVisible=true`로 남아있는 짧은 순간이 있을 수 있어 방어적으로 유지.
  - **여기까지 오는 데 실패한 시도가 세 번 있었습니다. 전부 5번 표에 정리되어 있고, 아래 순서대로 되살리지 마세요:**
    1. `IActivityTaskManager.moveTaskToBack(int)` 리플렉션 — 호출은 성공하지만 z-order만 바꿀 뿐 렌더링을 막지 않음. One UI의 5-window 캡이 우연히 같은 결과를 내서 처음엔 통한 것처럼 보였다가, 사용자가 창을 옮기자 뒤의 "치웠어야 할" 창이 다시 보이는 걸로 반증됨.
    2. `resizeTask()`로 usable area 밖(`area.right + 10000px`)에 파킹 — `dumpsys`상 좌표는 확실히 화면 밖이었지만, **One UI의 desktop 레이아웃 관리자가 주기적으로 창을 화면 가장자리로 다시 끌어당김(clamp)**. 사용자가 "그냥 엣지로 밀려날 뿐"이라고 직접 지적해서 발견.
    3. `wm shell desktopmode minimizeAll <displayId>` (shell 명령) — 첫 단독 테스트는 성공했는데, 재현하니 (a) `apply()`처럼 launch 직후 딜레이 없이 붙이면 실패, (b) 나중엔 단독 호출조차 실패. 근본 원인(어떤 desk 상태에서 통하고 안 통하는지) 불명.
    4. `com.samsung.android.multiwindow.IMultiTaskingBinder`를 **"activity_task" 바인더를 직접 재캐스팅**해서 얻으려 한 시도 — `SecurityException: Binder invocation to an incorrect interface`. `activity_task` 바인더는 `IActivityTaskManager`만 구현하고 있었음. 진짜 접근 경로는 `IActivityTaskManager` 자체의 `getMultiTaskingBinder()` 메서드였고, 이건 `atmClass.methods`를 전부 덤프하는 임시 진단 로그로 찾음.
  - `am task remove`로 완전히 닫는 방안도 고려했지만, 앱 상태를 보존하고 싶다는 사용자 선택으로 최소화 쪽을 택함.
  - **덤으로 발견한 것 — `com.samsung.android.multiwindow.MultiWindowManager`.** 사용자가 궁금해해서 별도로 한 번 더 조사(임시 진단 로그로 전체 시그니처 덤프 + 실제 호출). 지금 구현은 이걸 안 씁니다 — 이미 검증된 `IMultiTaskingBinder` 경로를 건드릴 이유가 없어서 — 하지만 다음에 리팩터링할 때 후보로 적어둡니다.
    - `getInstance(): MultiWindowManager` — **인자 없는 static 팩토리, 실제로 호출해서 확인됨** (`Context` 불필요).
    - `getVisibleTasks(): List<TaskInfo>` / `getVisibleTasks(int displayId): List` / `getTaskInfoFromPackageName(String): List` — Samsung 자체 `TaskInfo`를 돌려주는데, 지금 `WindowTilingServiceImpl.getVisibleTaskInfo()`가 `IActivityTaskManager.getTasks()` 결과에서 필드를 하나하나 리플렉션으로 꺼내는 것보다 필드가 훨씬 많고(`isFocused`, `resizeMode`, `token`(WindowContainerToken), `positionInParent`, `lastNonFullscreenBounds` 등) 바로 씀직합니다. **`getVisibleTaskInfo()`/`getVisibleTaskPackages()`를 이걸로 단순화할 여지 있음** — 실제 호출까지 확인했으니 다음 리팩터링 후보 1순위.
    - `getMinimizedFreeformTasksForCurrentUser(): List` — 뭐가 최소화돼 있는지 직접 물어볼 수 있음. 실제 호출로 확인.
    - `minimizeAllTasks(int displayId): boolean` / `minimizeTaskById(int): boolean` — 지금 쓰는 `IMultiTaskingBinder.minimizeTaskById`와 사실상 같은 계열이라 신뢰할 수 있을 걸로 보이지만 (호출은 안 해봄) `wm shell desktopmode minimizeAll`(실패한 3번 시도)과는 다른 경로라는 점에 주의 — 이름이 비슷해도 안 통했던 그거랑 다른 API입니다.
    - `inDesktopWindowing(): boolean` — 실제로 호출하니 `false`가 나왔는데, 그 순간 창이 여러 개 떠 있었으니 **정확한 의미가 불확실**합니다 (호출 프로세스 관점의 뭔가일 수도). 결론 내리지 말 것.
    - 그 외 존재만 확인(호출 안 해봄): `removeFocusedTask(int): boolean`, `toggleFreeformWindowingMode(): boolean`, `getSupportedMultiWindowModes(ActivityInfo|ResolveInfo): int`, `getResizeMode(ActivityInfo): int`, `supportsMultiWindow(IBinder): boolean`, `isAllowedMultiWindowPackage(String): boolean`, `registerFreeformCallback(IFreeformCallback)`.
- **`WindowTilingServiceImpl`은 shell UID로 실행됩니다.** 여기서 명령을 실행할 때 `sh -c` + 문자열 보간을 쓰면 안 됩니다 — 패키지명은 SharedPreferences와 바인더에서 오는 신뢰할 수 없는 입력이고, 여기서의 injection은 shell 권한으로 실행됩니다. `exec(vararg args)`로 argv를 직접 넘기고 `PACKAGE_NAME` / `COMPONENT_NAME` 정규식으로 검증한 뒤 쓰세요. (초기 구현이 이 실수를 했다가 고쳤습니다.)

### 자동 재배치 (opt-in, `TilingAccessibilityService.onAccessibilityEvent`)
사용자가 명시적으로 요청한 "보수적인 버전"입니다: **마지막으로 불러온(load) scene에 속한 앱이 죽었다가 다시 나타날 때만, 그 창만** 저장된 위치로 되돌립니다. 그 외 창·다른 scene의 앱·단순 포커스 전환은 절대 건드리지 않습니다.

- 트리거 판별은 **taskId 비교**입니다 (`TYPE_WINDOW_STATE_CHANGED` 이벤트 + `lastTaskIdByPackage` 맵). 같은 taskId면 포커스만 바뀐 것이라 무시, taskId가 바뀌었으면 재실행된 것이라 재배치. 실시간 타일링(3번 참고)으로 되돌아가지 않도록 이 구분이 핵심입니다.
- `onLoadScene()`이 호출될 때마다 `activeScene`을 갱신하고 `lastTaskIdByPackage`를 비웁니다 — 그래서 scene을 새로 불러온 직후 각 앱의 첫 이벤트는 (이미 올바른 위치인데도) 한 번 더 resize가 걸리지만, 이건 무해하고 taskId를 기록하는 부수효과가 있어 의도한 동작입니다.
- **이 세션에서 스위치 토글/영속화만 실기기 확인했고, 실제 트리거(scene 로드 → 앱 하나를 force-stop → 재실행 → 자동 재배치 확인)는 아직 안 해봤습니다.** 다음 세션에서 검증 필요.

---

## 5. Android 16 / 이 기기에서 밟은 지뢰들 (전부 실측 확인)

| 문제 | 사실 | 대응 |
|---|---|---|
| `IActivityTaskManager.setTaskWindowingMode(int,int,boolean)` | **API 36에 존재하지 않음** (`NoSuchMethodException`) | `launchInFreeform()`이 `am start --windowingMode 5`로 우회. 실행 중인 fullscreen 앱도 freeform으로 전환됨 |
| `TaskInfo.bounds` | **그런 필드 없음** (`NoSuchFieldException`) | `configuration.windowConfiguration.getBounds()` 사용 |
| `wm size` | 패널의 natural orientation을 반환 (회전 무시) | `dumpsys window displays`의 display 0 `app=` 값 사용 |
| `user_rotation` 강제 | **One UI가 폴더블 내부 화면에서 무시함** | 강제 회전 포기, 현재 방향 기준으로 계산 |
| `Log.d` | **`:tiling`(shell UID) 프로세스에서 필터링되어 안 보임.** `Log.e`는 보임 | 진단은 `Log.e`로 하거나 `adb shell setprop log.tag.TilingWM VERBOSE` |
| APK 재설치 | **접근성 서비스가 시스템에 의해 등록 해제됨** | 아래 6번의 재등록 명령 필요 (또는 `scripts/deploy.sh`) |
| `PackageManager.queryIntentActivities()` | **API 30+ 패키지 가시성 제한 때문에, `<queries>` 매니페스트 선언 없이는 앱이 다른 앱을 거의 못 봄.** `CATEGORY_LAUNCHER`는 자동 예외 대상이 아님 (그건 `CATEGORY_HOME`만 해당) | `AndroidManifest.xml`에 `ACTION_MAIN`+`CATEGORY_LAUNCHER` `<queries><intent>` 선언 추가함. `PresetBuilderDialog`의 앱 선택기가 실사용 가능하려면 필수 |
| `adb shell screencap -d <id>` / `adb shell input -d <id>` | **두 커맨드가 서로 다른 display-id 네임스페이스를 씁니다.** `screencap -d`는 `dumpsys SurfaceFlinger --display-id`가 주는 거대한 물리 ID(`4630947200649055635` 등)를 기대하고, `input -d`는 `dumpsys window displays`의 논리 ID(`0`, `1`)를 기대합니다. 서로 바꿔 쓰면 각각 "Display Id not valid" 에러 또는 (에러 없이) 엉뚱한 곳에 입력이 감 | 폴더블 내부 화면 = 논리 `mDisplayId=0` (`input -d 0 ...`), physical HWC display 0 (`screencap -d 4630947200649055635 ...`). 둘 다 명시하지 않고 "auto-detect"에 맡기면 가끔은 맞지만 신뢰 불가 |
| `adb shell input text "..."` | **기기의 활성 IME가 한국어(Hangul 조합) 상태면, 순수 ASCII 문자열도 자모 조합을 거쳐 깨진 텍스트가 됩니다** (`Work Setup` → `째가ㄴㄷ셔ㅔ` 같은 식). 앱 버그 아님, 테스트 툴링 한계 | 자동화 텍스트 입력 검증은 숫자만 쓰거나(`99` 등 자모 조합 안 걸림), IME 상태를 신경 쓰지 않아도 되는 경우로 제한. 실제 문자열 렌더링 자체는 스크린샷으로 별도 확인 |
| 플로팅 오버레이(펼쳐진 알림 pill 등) | **`FLAG_NOT_FOCUSABLE` 오버레이도 자기 영역의 터치는 삼킵니다.** 화면 상단에 떠 있던 Slack 알림 pill이 `GRANT PERMISSION` 버튼 탭을 두 번 연속 씹었음 (WindowManager 포커스는 계속 MainActivity였는데도) | 좌표 탭 전에 스크린샷/uiautomator dump로 다른 오버레이가 그 영역을 덮고 있지 않은지 확인. 애매하면 버튼 bounds 안에서 오버레이 반대쪽 가장자리를 노려서 탭 |
| `IActivityTaskManager.moveTaskToBack(int)` | 리플렉션 호출 자체는 성공하지만 (`NoSuchMethodException` 없음), **z-order만 바꿀 뿐 화면 렌더링을 막지 않습니다.** freeform 창이 화면을 100% 안 덮으면(창 이동/리사이즈 등) 뒤로 보낸 창이 그 틈으로 여전히 보입니다. One UI의 5-window desktop 캡이 우연히 같은 결과를 내서 처음엔 "된 것처럼" 보였습니다 | 쓰지 마세요. 대신 `IWindowTilingService.minimizeTask()` (아래 참고) |
| `resizeTask()`로 usable area 밖에 창을 옮기기 ("화면 밖 파킹") | `dumpsys`가 보여주는 논리 좌표는 확실히 화면 밖(`area.right+10000px`)인데도, **One UI의 desktop 레이아웃 관리자가 창을 주기적으로 화면 가장자리로 다시 끌어당깁니다(clamp).** 실제로는 안 사라지고 얇게 삐져나와 보임 | 쓰지 마세요. 창을 화면에서 완전히 안 보이게 하려면 리사이즈/이동이 아니라 **진짜 최소화**(`minimizeTask`)가 필요합니다 |
| `wm shell desktopmode minimizeAll <displayId>` | 첫 단독 실행은 성공(화면이 실제로 깨끗해짐)했지만 **재현성이 없습니다.** `apply()`처럼 launch 직후 딜레이 없이 이어붙이면 실패, 나중엔 완전히 단독으로 호출해도(1.5초 대기 후에도) 실패. 정확히 어떤 desk 상태에서 통하는지 못 찾음 | 쓰지 마세요. `IWindowTilingService.minimizeTask(taskId)`가 개별 task를 대상으로 하며 신뢰할 수 있습니다 |
| `com.samsung.android.multiwindow.IMultiTaskingBinder` | **`"activity_task"` 바인더를 그대로 재캐스팅해서 얻으려 하면 `SecurityException: Binder invocation to an incorrect interface`가 납니다** — 그 바인더는 `IActivityTaskManager`만 구현합니다. 네이티브 캡션바 `-` 버튼을 실제로 누르고 logcat을 지켜봐서 정확한 호출 경로(`IMultiTaskingBinder$Stub.onTransact` → `MultiTaskingBinder.minimizeTaskById` → `Task.moveTaskToBack`+`Task.setMinimized`)를 확인했고, `IActivityTaskManager`의 전체 메서드를 덤프해서 진짜 접근자를 찾음 | **정답은 `IActivityTaskManager.getMultiTaskingBinder(): IMultiTaskingBinder`** — 이미 연결되어 있는 `atm` 객체에서 바로 얻으면 됩니다. `WindowTilingServiceImpl.minimizeTask()`가 이걸 구현합니다 |
| 디버그 서명 키 | **머신마다 `~/.android/debug.keystore`가 다르게(랜덤) 생성됨.** macOS에서 설치된 앱을 다른 머신에서 빌드한 APK로 `adb install -r` 하면 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`로 거부됨 | 이번엔 `adb uninstall` 후 재설치로 즉시 해결(사용자 선택, 10번 참고). **근본 해결(프로젝트 전용 debug 키스토어를 repo에 커밋)은 아직 안 함** |

또한 One UI에는 `Task{#7 name=Desk}` / `MinimizedDesk_7` 같은 **Desktop Windowing 컨테이너**가 있고 그 아래 앱들이 자식 task로 들어갑니다. 지금은 문제되지 않았지만, desk를 활성화한 상태에서 task를 훑을 때는 부모/자식 구분이 필요할 수 있습니다.

---

## 6. 개발 워크플로

### 빌드 + 설치 + 접근성 재등록 + 실행 (한 번에)
```bash
./scripts/deploy.sh              # 기존 APK 설치 + 접근성 재등록 + 실행
./scripts/deploy.sh --build      # 위에 더해 assembleDebug도 먼저 실행 (macOS 전용 JAVA_HOME 탐지)
./scripts/deploy.sh --no-launch  # 실행은 생략
```
macOS에서는 `--build`가 그대로 동작합니다. Linux/OCI 세션처럼 SDK가 따로 필요한 환경에서는 2번 항목의 Docker 빌드로 APK를 만든 뒤 `deploy.sh`(빌드 플래그 없이)로 설치·등록·실행만 맡기면 됩니다.

### (수동으로 할 경우) 빌드 + 설치
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
ANDROID_HOME="$HOME/Library/Android/sdk" gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### (수동으로 할 경우) 재설치 후 접근성 서비스 재등록
**기존 서비스를 덮어쓰면 안 됩니다.** 이 기기에는 bitwarden, kdeconnect, simplewear, 삼성 게임부스터가 이미 등록되어 있습니다. 반드시 append (`scripts/deploy.sh`가 이 로직을 대신 해줌):

```bash
SVC="dev.atwm.tilingwm/dev.atwm.tilingwm.service.TilingAccessibilityService"
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) ;; *) adb shell settings put secure enabled_accessibility_services "$CUR:$SVC";; esac
adb shell settings put secure accessibility_enabled 1
```

### 앱 켜기
앱 실행 후 **`SHOW FLOATING WIDGET` 버튼을 눌러야** 위젯이 뜹니다 (`TilingAccessibilityService.isEnabled`). 좌표 탭으로 자동화할 때는 창 위치가 매번 달라지므로 스크린샷/uiautomator dump로 확인하고 누르세요. 좌표 탭 관련 함정은 5번 표 참고.

### 로그
```bash
adb logcat -d | ggrep 'TilingWM'
```

### PoC 스크립트
`scripts/tile.sh`는 앱과 별개로 동작하는 ADB 기반 PoC입니다. macOS에서 동작하도록 고쳐두었고, 기기 상태를 정리할 때 유용합니다:
```bash
./scripts/tile.sh reset     # 전체화면 복귀 + 자동회전 원복
./scripts/tile.sh list      # freeform task 목록
```

---

## 7. 검증된 동작 (실측)

이전 세션:
- freeform 실행 + `am task resize`로 타일링 → **정확히 요청한 좌표에 배치됨** (`mBounds`가 픽셀 단위로 일치)
- 네이티브 캡션바(`...` `−` `⤢` `✕`)가 삼성 기기에서도 AOSP 그대로 렌더링됨
- 플로팅 위젯 표시 → puck `(24,320) 104x104`, 패널 확장/축소 정상
- scene 저장 → `Saved 'Scene 1' (2 windows)` (앱 자신은 `excludedPackages`로 제외됨)
- scene 복원 → 계산기를 `force-stop`으로 완전히 죽인 뒤에도 재실행되어 정확한 위치에 배치. 전 과정 ~320ms, 첫 재시도(200ms)에서 완료

**2026-08-12 세션 (uiautomator dump + 스크린샷으로 전 과정 확인, 크래시 로그 없음):**
- MainActivity 새 UI 전체 렌더링 정상 (Shizuku 상태, Auto-Restore 스위치, 4개 프리셋, Saved Layouts 빈 상태 문구)
- Shizuku 권한 허용 플로우 정상 (`항상 허용` 탭 → `SHOW/HIDE FLOATING WIDGET` 버튼 전환)
- 프리셋 탭 → `PresetBuilderDialog` 오픈 → 앱 선택기(**실제 설치 앱 전체 목록**, `<queries>` 수정 검증됨) → 슬롯 2개 채움 → `Create` 버튼 자동 활성화 → 이름 다이얼로그(기본값 `Scene 1`) → 저장 → 토스트 `Saved 'Scene 1'.` → 목록에 `Scene 1 (2 windows)` 표시
- Rename → `store.rename()`으로 이름 변경, 목록 내 위치 유지 확인 (`Scene 1` → `99`)
- Delete → 확인 다이얼로그(`Delete '99'? This can't be undone.`) → 삭제 → 빈 상태로 복귀
- `scripts/deploy.sh`로 설치+접근성 재등록(기존 4개 서비스 보존 확인)+실행 전 과정 정상

**추가 세션 (같은 날, scene 전환 시 이전 창 정리 기능):** 사용자가 실사용 중 "scene을 연속으로 불러오면 이전 scene 창이 새 scene 위/옆에 남는다"는 버그를 직접 발견해서 리포트.

1차 시도(`moveTaskToBack`)를 사용자의 실제 scene(Scene 1=2창, Scene 2=3창)으로 검증했을 때는 **성공한 것처럼 보였습니다** — Scene 1 로드 → Scene 2 로드 시 Scene 1의 창이 화면에서 사라짐 (One UI가 "앱은 동시에 5개까지 화면에 표시돼요. 사용한지 가장 오래된 앱 크기를 최소화했어요" 토스트까지 띄움). **하지만 이건 우연이었습니다.** 사용자가 Scene 2의 창 하나(Drive)를 손으로 살짝 옮기자 그 틈으로 Scene 1의 창(Claude, Chrome)이 다시 나타났습니다 — Scene 2가 화면을 100% 덮고 있었을 때만 안 보였을 뿐, `moveTaskToBack`은 실제로 화면 렌더링을 막지 않았던 것입니다 (One UI의 5-window 캡이 우연히 같은 결과를 냈던 것으로 추정).

2차 시도(`resizeTask`로 화면 밖 파킹)도 사용자가 재현해서 반증: "그냥 엣지로 밀려날 뿐"이라고 지적 — One UI가 창을 화면 가장자리로 다시 clamp함.

3차 시도(`wm shell desktopmode minimizeAll`)는 껐다 켰다 하는 재현성 문제로 스스로 폐기.

4차이자 최종: 네이티브 `-` 버튼을 실제로 눌러서 logcat으로 정확한 호출 경로를 확인하고(`IMultiTaskingBinder.minimizeTaskById`), `IActivityTaskManager.getMultiTaskingBinder()`가 진짜 접근자임을 진단 로그로 찾아서 구현. **검증 방법은 동일**: Scene 1 로드 → Scene 2 로드 → `am task resize`로 Drive 창을 사용자가 한 것과 똑같이 억지로 줄이고 옮김 → 그 틈으로 Scene 1이 아니라 완전히 다른(이 세션과 무관한, 훨씬 이전부터 백그라운드에 있던) 앱들만 보임. `dumpsys activity activities`로 Claude/Chrome task 확인 → **`visible=false, visibleRequested=false`** (진짜로 최소화됨, 좌표를 옮긴 게 아니라). `minimizeTask()` 호출에서 예외 없음(`minimizeTask(...) failed` 로그 없음). 사용자가 직접 기기를 정리하고 위젯을 띄운 뒤 재확인까지 완료.

플로팅 위젯 자체의 캡처/복원 버튼도 이번에 다시 확인됨 (사용자의 실제 scene을 불러오는 방식으로). **아직 검증 안 한 것**: 자동 재배치(auto-restore)의 실제 트리거(scene 로드 → 앱 하나 force-stop → 재실행 → 자동 재배치 확인).

---

## 8. 완료된 작업 (2026-08-12 세션)

전부 실기기 검증까지 끝냄. 상세는 4·7번 참고.

- ✅ **MainActivity 정리** — 문구를 현재 기능(위젯 표시/숨김)에 맞게 수정. `PresetBuilderDialog`(신규, `ui` 패키지)로 프리셋 → 슬롯별 앱 선택 → 이름 지정 → 저장 흐름 구현
- ✅ **scene 이름 변경** — `SceneStore.rename()`(위치 보존, 이름 충돌 거부) + MainActivity 목록의 Rename 버튼
- ✅ **개발 편의 스크립트** — `scripts/deploy.sh` (설치 + 접근성 재등록 + 실행, `--build`/`--no-launch` 플래그)
- ✅ **자동 재배치 (opt-in)** — `SceneStore.autoRestoreEnabled`(기본 꺼짐) + taskId 비교 기반 보수적 트리거

부수적으로:
- `PresetBuilderDialog`/`TilingAccessibilityService`/`MainActivity`가 공유하는 `engine/UsableArea.kt` 추출 (중복 제거)
- `SceneStore.nextAvailableName()` 추출 (기존 private 메서드를 공개해서 MainActivity도 재사용)
- `TilingAccessibilityService.refreshWidget()` 추가 — MainActivity에서 scene을 만들거나 이름 바꾸거나 지우면, 열려 있는 위젯 패널도 갱신되도록
- `AndroidManifest.xml`에 `<queries>` 추가 (5번 표 참고 — 빌드 후 리뷰 중 발견한 실제 버그)
- ✅ **scene 전환 시 이전 창 정리** — 사용자가 실사용 중 리포트한 버그. 네 번의 시도 끝에 Samsung의 `IMultiTaskingBinder.minimizeTaskById()`(네이티브 캡션바 `-` 버튼과 동일 경로)로 정착, `dumpsys`의 `visible=false` 확인까지 포함해 실기기 검증 완료 (4·5·7번 참고). **`moveTaskToBack`, 화면 밖 파킹, `wm shell desktopmode minimizeAll`은 전부 시도했다가 실패로 확인되어 폐기 — 되살리지 마세요** (5번 표)
- ✅ **회전 시 창 배치 재계산** — 회전해도 화면 밖으로 안 깨지도록 `TilingAccessibilityService.onConfigurationChanged()`(실제 orientation flip일 때만) → `SceneManager.reapplyBounds()`(relaunch 없이 좌표만 재계산·재적용)로 처리. **사용자가 직접 기기를 돌려서 검증함.** 단, `MasterStackLayout` 계열 프리셋은 만들어질 때 방향의 topology(세로: master 왼쪽 / 가로: master 위쪽)가 비율로 고정되므로, 회전해도 안 깨지긴 하지만 반대 방향에 최적화된 배치로 자동 전환되지는 않음 — 다음 개선 후보
- ✅ **상/하단 여백 제거** — `TilingConfig.statusBarHeight`/`navBarHeight`가 실제 크기가 아니라 고정 추정치(100px)였던 게 원인. `usableArea()`를 `WindowManager.getMaximumWindowMetrics()` + `WindowInsets.Type.systemBars()`로 실제 인셋을 읽도록 재작성 (`getCurrentWindowMetrics()`가 아님 — 그건 호출하는 창 자신의 크기라, MainActivity가 작은 freeform 창일 때 잘못된 값이 나옴). 이 두 필드의 유일한 소비자였던 `TilingEngine.kt`(실시간 타일링 시절 죽은 코드)와 그게 쓰던 `LayoutBounds`/`TaskInfo` 모델도 같이 삭제. **사용자가 직접 검증함.**

---

## 9. 지금 상태

기능적으로 완성입니다. 사용자가 원래 요청한 것(좌/우 분할 + 슬롯에 원하는 앱 지정 가능한 프리셋, scene 저장/복원, 이름 변경)이 전부 동작합니다.

### Release 빌드 + GitHub Actions
`LuterGS/android-custom-aod`의 `release.yml`을 참고해 `.github/workflows/release.yml` 추가함 (`workflow_dispatch` 수동 트리거 → `app/build.gradle.kts`의 `versionName`으로 서명된 release APK 빌드 → `v<versionName>` GitHub Release 생성). 이 repo와의 차이 때문에 조정한 부분:
- JDK 17 → **21** (AGP의 `JdkImageTransform`이 21 아니면 `jlink`에서 실패 — 2번 참고)
- `gradlew`가 없어서 (2번 참고) `setup-gradle`에 `gradle-version: "9.4.0"` 명시하고 `./gradlew` 대신 `gradle`로 직접 호출

**release 서명 키스토어를 새로 만들어서 GitHub secrets 4개(`RELEASE_KEYSTORE_BASE64`/`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`)에 등록완료.** `app/build.gradle.kts`에 `RELEASE_KEYSTORE_PATH` 환경변수가 있을 때만 활성화되는 `signingConfigs.release`를 추가함 — 로컬에서 그 변수 없이 `assembleRelease` 해도 그냥 unsigned로 빌드될 뿐 안 깨짐. **키스토어 원본 파일 + 비밀번호는 GitHub secrets에 다시 못 읽어오므로, `SendUserFile`로 사용자에게 직접 전달함 (repo에는 커밋 안 함, `.gitignore`에 `*.keystore`/`*.jks` 추가).** 사용자가 안전한 곳에 백업했는지는 다음 세션에서 확신할 수 없으니, 이 키스토어가 아예 사라진 것 같으면 새로 만들어야 한다고 먼저 물어보세요 — 그러면 그 전에 배포된 release와는 다른 서명이 됩니다.

이 debug keystore 이슈(아래 10번 1)와는 **별개**입니다 — release 서명은 이제 CI에서 고정된 하나의 키로 항상 만들어지므로 안정적이고, debug keystore(로컬 개발용, 여러 머신 오갈 때 `adb install -r` 충돌 나는 것)는 여전히 미해결.

---

## 10. 남은 것 / 알려진 이슈

우선순위 낮은 순으로, 강제된 작업은 아닙니다:

1. **디버그 서명 키가 머신마다 다름** (5번 표 참고). macOS와 이 세션의 Linux 환경을 번갈아 쓰면 `adb install -r`이 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`로 계속 막힐 수 있습니다. 근본 해결책은 프로젝트 전용 debug keystore를 만들어 `app/build.gradle.kts`의 `signingConfigs.debug`에 지정하고 repo에 커밋하는 것 — 다음에 이 문제가 또 나오면 사용자에게 제안하세요. (release 서명 키와는 별개 — 9번 참고)
2. **자동 재배치의 실제 트리거가 미검증** (7번 참고). scene을 로드하고, 그 안의 앱 하나를 force-stop한 뒤 재실행해서 정말 제자리로 돌아오는지 확인 필요.
3. 앱 아이콘이 시스템 기본값 그대로입니다 (`AndroidManifest.xml`에 `android:icon` 없음). 요청받은 적 없어서 손대지 않았습니다.
4. `PresetBuilderDialog`의 앱 선택기는 label만 보여주고 검색/필터가 없습니다 — 설치 앱이 아주 많아지면 (지금 기기엔 이미 수십 개) 스크롤이 길어집니다. 문제 제기 없었으니 선제 작업은 안 했습니다.

---

## 11. 사용자에 대해 알아두면 좋은 것

- 한국어로 소통합니다.
- 기기가 **실사용 중인 개인 폰**입니다. `adb shell input tap` 같은 자동 조작을 할 때는 사용자가 기기를 쓰고 있지 않은지 확인하세요 (한 번 사용자 조작을 방해한 적 있음). **2026-08-12 세션에서는 매번 명시적으로 확인 후 진행했고, 실제로 폰을 안 쓰는 중이라며 자동 탭 검증을 허가했습니다** — 확인 자체는 매번 필요하다고 보는 게 맞습니다 (한 번 허락받았다고 다음에도 안 물어봐도 되는 건 아님).
- 접근성 서비스 설정처럼 **다른 앱에 영향을 주는 시스템 설정을 건드릴 때는 기존 값을 반드시 보존**하세요.
- 설계 판단을 스스로 하는 편이고, 트레이드오프를 설명하면 명확히 결정해줍니다. "정확한 게 최고"라며 복잡하더라도 정확한 쪽을 고른 적도 있고, 반대로 실시간 타일링처럼 복잡도 대비 이득이 없으면 과감히 방향을 바꿉니다. 디버그 키스토어 문제처럼 stakes가 낮으면 "근본 해결"보다 "지금 당장 빠른 해결"을 고르기도 합니다 (10번 참고) — 매번 최대주의를 기대하진 않는 편.
- Google Play 배포 계획은 없고 **sideload 용도**입니다. 그래서 접근성 권한 요구를 감수하기로 했습니다.
