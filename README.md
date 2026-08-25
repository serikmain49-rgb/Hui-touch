# HUI-TOUCH — универсальный гироскоп для игр (Android NDK + Shizuku)

Приложение превращает наклон/поворот устройства в эмуляцию касаний (свайп
между двумя точками на экране) в любом приложении — без модификации игры.
Датчики читаются **напрямую из C++** (`ASensorManager`, до 200 Гц), касания
инжектятся через **скрытый `InputManager.injectInputEvent`** в привилегированном
процессе **Shizuku** (`INJECT_INPUT_EVENT_MODE_ASYNC`).

> ⚠️ **Дисклеймер.** Инструмент инжектит системный ввод в любые приложения.
> Использование в онлайн-играх может нарушать их правила и обнаруживаться
> античитом — на свой риск, в пределах ваших обязательств перед держателями
> лицензии.

---

## Архитектура

```
┌────────────────────────────── ПРОЦЕСС ПРИЛОЖЕНИЯ (com.huitouch) ─────────────────────────────┐
│                                                                                              │
│  MainActivity            OverlayService (foreground)                                          │
│  ├ Shizuku-перм.          ├ Плавающая панель: скриншот, точки A/B, слайдер 1..100, ось        │
│  ├ overlay-перм.          ├ MediaProjection → ImageReader → Bitmap (скриншот)                │
│  └ запуск панели          ├ Pill-окно (⏹ быстрая остановка)                                   │
│       │                   └生命周期 эмуляции                                                     │
│       │                          │                    ▲                                       │
│       │                   GyroEngine (JNI)      onSample(x, y, angle, tsNs)                  │
│       │                          │                    │  (вызов ПРЯМО из sensor-потока,       │
│       ▼                          ▼                    │   без Handler/Looper)                 │
│  ShizukuChannel ──── binder (IShizukuTouch) ──────────┘                                       │
│  (bindUserService)        │      TouchInjector: MotionEvent.obtain(DOWN/MOVE/UP)              │
└────────────────────────────┼──────────────────────────────────────────────────────────────────┘
                             │  (1 binder hop)
┌────────────────────────────▼──────────────────────────────────────────────────────────────────┐
│  ПРОЦЕСС Shizuku (app_process; uid 0 — root, uid 2000 — adb)                                  │
│                                                                                              │
│  com.huitouch.ShizukuTouchService extends IShizukuTouch.Stub                                  │
│     injectEvent(MotionEvent, mode=ASYNC)                                                     │
│        └ рефлексия (кэшируется 1 раз):                                                         │
│            ServiceManager.getService("input")  →  IInputManager.Stub.asInterface              │
│            → InputManager.injectInputEvent(InputEvent, 0)   ← скрытый метод AOSP              │
└────────────────────────────┼──────────────────────────────────────────────────────────────────┘
                             │  (system_server)
                        InputDispatcher → игра получает ACTION_DOWN/MOVE/UP в координатах A..B
```

**Три процесса — три уровня привилегий:**

| Процесс | Кто | Что делает |
|---|---|---|
| `com.huitouch` | обычное приложение | UI, панель, скриншоты, сборка `MotionEvent` |
| Shizuku user service | `com.huitouch.ShizukuTouchService` в процессе Shizuku, uid 0/2000 | вызов скрытого `InputManager.injectInputEvent` (non-SDK API здесь разрешены) |
| `system_server` | система | `InputDispatcher` доставляет события игре |

Почему инжест через привилегированный процесс, а не «просто» из приложения:
`injectInputEvent` — скрытый метод, требующий привилегий `INJECT_EVENTS`
(обычное приложение его вызвать не может; Shizuku даёт uid shell/root, для
которого это разрешено).

Почему датчики — в C++: `ASensorEventQueue` с `SENSOR_DELAY_FASTEST` даёт
кадры датчика напрямую в нативный callback на частоте SoC (50–200 Гц), без
Java-прослойки (`SensorManager`/`Looper`), без аллокаций на hot-path.

---

## Структура проекта

```
Hui-touch/
├── build.gradle                      # AGP 8.10.0 + Kotlin 2.1.21 (как в официальном demo Shizuku)
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle                  # compileSdk 35, minSdk 26, NDK/CMake, AIDL, Shizuku API
    ├── proguard-rules.pro            # keep для класса user service (если включить R8)
    └── src/main/
        ├── AndroidManifest.xml       # overlay/FGS-перм., ShizukuProvider, specialUse FGS
        ├── aidl/com/huitouch/
        │   └── IShizukuTouch.aidl    # интерфейс приложения <-> user service (destroy=16777114!)
        ├── cpp/
        │   ├── CMakeLists.txt
        │   └── native-lib.cpp        # ★ C++ NDK движок: гироскоп -> угол -> A..B -> JNI
        ├── java/com/huitouch/
        │   ├── App.kt
        │   ├── MainActivity.kt       # разрешения, Shizuku-авторизация, мост Activity-флоров
        │   ├── OverlayService.kt     # ★ плавающая панель + pill + скриншоты + старт/стоп
        │   ├── ShizukuTouchService.kt# ★ user service: инъекция в InputManager (рефлексия)
        │   ├── engine/GyroEngine.kt  # JNI-обёртка C++ движка
        │   ├── injection/TouchInjector.kt  # MotionEvent DOWN/MOVE/UP + замер задержки
        │   ├── shizuku/ShizukuChannel.kt   # bindUserService + holder binder'а
        │   └── ui/ScreenshotView.kt  # скриншот + перетаскиваемые точки A/B
        └── res/…                     # layout панели, тёмная тема, иконки
```

---

## Сборка

1. **Android Studio** (Igor или новее), **NDK r25+** (Settings → SDK Manager → NDK).
2. Open project → Gradle sync.
   * Если Studio ругается на wrapper'ный jar (в репозиторий бинарник не включён):
     `gradle wrapper --gradle-version 8.11.1` один раз, либо в Settings → Build Tools →
     Gradle выберите «Use Gradle from: specific file path» на вашу установку Gradle 8.11+.
3. `Run 'app'` на устройство (или эмулятор x86_64 с виртуализацией датчиков).
   NDK зафиксирована на r27 (`27.0.12077973`) — SDK Manager сам предложит установить.

### APK через GitHub Actions (без локального Android Studio)

Workflow лежит в `ci/build-apk.workflow.yml`. Активировать (одна команда,
нужны права владельца репозитория — файлы в `.github/workflows/` нельзя
пушить сервисными токенами без special permission `workflows`):

```bash
mkdir -p .github/workflows
git mv ci/build-apk.workflow.yml .github/workflows/build-apk.yml
git push
```

Дальше:
1. Вкладка **Actions** → **Build APK** → **Run workflow**.
2. В `release_tag` введите тег, например `v1.0.0` (или оставьте пустым).
3. Через ~5–10 минут: APK в **Releases** (если задан тег) и в артефактах запуска.

Это debug-сборка (подписана debug-ключом раннера) — ставится на телефон напрямую.
Для signed release: создайте keystore, сохраните его как secret репозитория и
переключите цель сборки на `assembleRelease` с `signingConfigs`.

Зависимости (Maven Central): `dev.rikka.shizuku:api:13.1.5`,
`dev.rikka.shizuku:provider:13.1.5`, `androidx.core:core-ktx:1.15.0`.

## Настройка Shizuku (однократно)

1. Установите **Shizuku** (≥ 11, лучше последнюю) — https://shizuku.rikka.app/download/
2. Запустите Shizuku на устройстве: **QR/беспроводная отладка** (Android 11+),
   ADB с ПК или root — любой режим подойдёт (adb → uid 2000, root → uid 0).
3. В HUI-TOUCH: «Авторизовать Shizuku» → подтвердите в диалоге Shizuku.

## Использование

1. «Открыть панель калибровки» (разрешите overlay, когда попросит).
2. На панели: **📷 Скриншот** (живой снимок экрана) или **🖼 Галерея**
   (любая картинка — координаты масштабируются на разрешение экрана).
3. В заголовке панели показано **разрешение экрана** (напр. `1080×2400`) —
   по нему сверяйтесь с координатами в строке под картинкой.
4. Пальцем перетащите **A** (зелёная) и **B** (красная). Касание будет
   «ходить» вдоль A→B: наклон в одну сторону — к B, в другую — к A,
   нейтральное положение — середина.
5. **Скорость 1..100**: 100 — полный размах A↔B при ±2° наклона,
   1 — при ±200° (почти не реагирует).
6. **Ось** (по умолчанию **Z — Roll**, поворот устройства в плоскости экрана):
   X — Pitch (наклон вперёд/назад), Y — Yaw (поворот влево/вправо).
7. **▶ Запустить** — панель скроется, появится пилюля **⏹**.
   Наклоняйте устройство — в игре идёт непрерывный `ACTION_MOVE`.
   Стоп — тапом по пилюле или через уведомление (там же «Скрыть»/«Панель»).

Точки/слайдер/ось можно менять **на лету** во время эмуляции.

---

## Латентность (честный разбор)

Цепочка одного кадра при наклоне:

| Стадия | Типичное время |
|---|---|
| Кадр гироскопа (SENSOR_DELAY_FASTEST, 100–200 Гц) | 5–10 мс между кадрами |
| C++: фильтр + интеграция + маппинг (без аллокаций) | < 0.05 мс |
| JNI `CallVoidMethod` из sensor-потока (без Handler) | ~0.01–0.05 мс |
| `MotionEvent.obtain` + binder app→Shizuku service | ~0.3–1 мс |
| `InputManager.injectInputEvent(MODE_ASYNC)` → очередь InputDispatcher | ~0.3–1 мс |
| **Итого: датчик → попадание в системную очередь ввода** | **обычно 1–3 мс** |

Приложение **встроенно меряет** задержку «timestamp сенсора → вызов
injectEvent» и показывает её в статусе панели (`~N.N мс`). Полная доставка до
окна игры дальше зависит от InputDispatcher/SurfaceFlinger и vsync
(+2–8 мс в худших случаях) — это уже не управляется приложением.

Почему 1–3 мс реально, а не фантастика: `MODE_ASYNC` не ждёт доставки,
binder-каналы прогреваются при первом событии, рефлексивный вызов
`InputManager` кэшируется один раз, sensor-колбэк не проходит через Looper.

## Точки настройки (native-lib.cpp)

```cpp
kLpfAlpha     // 0.25 — коэффициент НЧ-фильтра (меньше = плавнее, больше = отзывчивее)
kDeadZoneRad  // 5e-4 рад ≈ 0.03° — игнор микро-джиттера
kMinMovePx    // 0.6 px — не слать MOVE без осмысленного смещения
// maппинг: halfRange = 200° / sensitivity
```

## Частые проблемы

| Симптом | Причина / решение |
|---|---|
| «Shizuku: не запущен» | Запустите Shizuku (QR/ADB/root), проверьте, что он работает |
| «Shizuku: нужен доступ» | Кнопка «Авторизовать Shizuku» → подтверждение в диалоге Shizuku |
| Панель не появилась | Разрешение «рисование поверх» → Settings → Overlay |
| «гироскоп недоступен» | На эмуляторе включите sensor virtualization; на реальном телефоне датчик есть всегда |
| Касание «уезжает» в сторону панели | Панель при активной эмуляции нечувствительна к касаниям; пилюля маленькая — её можно не трогать |
| В игре события не видны | Игра должна получать касания в зоне A..B; проверьте, что пилюля/панель не лежат на этой зоне, и что Shizuku работает (uid 0/2000 в статусе) |
| Задержка выше 3 мс | Термотроттлинг/фоновые процессы — снизьте нагрузку на SoC; сам конвейер (binder+ASYNC) на тёплом устройстве стабильно в пределах 1–3 мс |

## Технические примечания

* **AIDL-стаб `IInputManager` сознательно не генерировался.** В разных
  версиях Android транзакционный код и тип параметра
  `injectInputEvent()` отличаются (android-14: `in InputEvent ev`,
  ранние релизы: `in IInputEvent ev`; порядок методов в AOSP-файле тоже
  менялся). Рефлексия на скрытом `InputManager.injectInputEvent(InputEvent, int)`
  стабильна между релизами — см. `ShizukuTouchService.ensureInjector()`.
* **User service не объявлен в манифесте** — Shizuku запускает класс
  собственным `app_process` (проверено по `UserServiceManager` Shizuku-API).
* **`destroy() = 16777114`** в AIDL — резервный транзакционный код Shizuku,
  обязателен в каждом user service.
* **Окно панели** — `TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE`
  (не берёт фокус у игры); при активной эмуляции добавляется
  `FLAG_NOT_TOUCHABLE`, чтобы не перехватывать инжектированные события.
* **Скриншот** — `MediaProjection` → `VirtualDisplay` → `ImageReader`
  (RGBA_8888, с учётом row/pixel stride). Сессию можно переиспользовать.
* **Класс user service нельзя обфусцировать** — имя передаётся Shizuku
  строкой (keep-правила в `proguard-rules.pro`).
