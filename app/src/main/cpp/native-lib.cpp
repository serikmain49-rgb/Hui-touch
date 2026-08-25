// ============================================================================
//  HUI-TOUCH — нативный гироскоп-движок (NDK)
// ============================================================================
//
//  Архитектура hot-path (каждый такт гироскопа, до 200 Гц):
//
//   ASensorEventQueue (sensor-поток Android)
//        │  onSensorChanged() — без аллокаций
//        ▼
//   интеграция угла (1-е звено НЧ-фильтр + deadzone + gap-reset)
//        │
//        ▼
//   маппинг угла -> позиция между точками A и B  (x, y в пикселях экрана)
//        │
//        ▼
//   JNI CallVoidMethod прямо из sensor-потока (НЕТ Handler/Looper hop)
//        │
//        ▼
//   Java: GyroEngine.onSample() -> TouchInjector -> Shizuku binder
//        -> InputManager.injectInputEvent(MotionEvent, MODE_ASYNC)
//
//  Задержка "датчик -> вызов injectInputEvent" на современных устройствах
//  обычно 1.5–4 мс (встроенный замер в приложении, см. TouchInjector).
//
//  JNI surface (класс com.huitouch.engine.GyroEngine):
//    boolean nativeStart(cb, x1, y1, x2, y2, sensitivity, axis)
//    void    nativeUpdate(x1, y1, x2, y2, sensitivity, axis)   // live-перенастройка
//    void    nativeStop()
//    // cb — объект с методом onSample(float, float, float, long)
// ============================================================================

#include <jni.h>
#include <android/log.h>
#include <android/sensor.h>

#include <atomic>
#include <cmath>

#define HT_TAG "HuiTouchNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  HT_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  HT_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HT_TAG, __VA_ARGS__)

namespace {

// ----------------------------- настройки -----------------------------------
constexpr float kLpfAlpha    = 0.25f;   // 1-е звено НЧ на скорости гироскопа (сглаживание шумов)
constexpr float kMaxGap      = 0.050f;  // с; перерыв в событиях > 50 мс -> сброс интеграции
constexpr float kDeadZoneRad = 5.0e-4f; // ~0.03°; микро-джиттер не шлём
constexpr float kMinMovePx   = 0.6f;    // px; эмитим только осмысленное смещение
constexpr float kRad2Deg     = 57.29577951308232f;
constexpr float kDeg2Rad     = 0.017453292519943295f;
constexpr int   kAxisZ       = 2;       // по умолчанию — ось Z (Roll, поворот в плоскости экрана)

// ------------------------------- состояние ---------------------------------
struct Engine {
    // калибровка (пиксели экрана)
    float x1 = 0.f, y1 = 0.f, x2 = 0.f, y2 = 0.f;
    int   sensitivity = 50;   // 1..100
    int   axis = kAxisZ;      // 0 = X (Pitch), 1 = Y (Yaw), 2 = Z (Roll)

    // сенсор
    ASensorManager    *mgr   = nullptr;
    ASensorEventQueue *queue = nullptr;
    const ASensor     *gyro  = nullptr;

    std::atomic<bool> running{false};

    // интегратор — трогает ТОЛЬКО sensor-поток
    float   startAngle = 0.f;   // угол-якорь (в момент nativeStart)
    float   curAngle   = 0.f;   // текущий интегрированный угол
    float   lpfRate    = 0.f;   // отфильтрованная угловая скорость
    nsecs_t lastTs     = 0;

    float   lastX = -1e9f;      // последняя отправленная позиция (фильтр повторений)
    float   lastY = -1e9f;

    // JNI
    JavaVM    *jvm      = nullptr;
    jobject   callback = nullptr;   // global ref на GyroEngine
    jmethodID onSample = nullptr;   // (FFFL)V
};

Engine eng;

// Прикрепляем текущий (sensor-)поток к JVM один раз.
// Потоки sensor/event живут всё время процесса — detach не нужен.
JNIEnv *AttachEnv() {
    JNIEnv *env = nullptr;
    const int st = eng.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if (eng.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    } else if (st != JNI_OK) {
        return nullptr;
    }
    return env;
}

// Вызывается на sensor-потоке с частотой гироскопа (SENSOR_DELAY_FASTEST:
// 50–200 Гц в зависимости от устройства).
void OnSensorChanged(const ASensorEvent *ev) {
    if (!eng.running.load(std::memory_order_relaxed)) return;
    if (ev->sensor->type != ASENSOR_TYPE_GYROSCOPE) return;

    // Только датчик в координатах устройства (X вправо, Y вверх, Z из экрана)
    const ASensorLocation *loc = ASensor_getSensorLocation(ev->sensor);
    if (loc == nullptr || loc->coordinateSystem != ACOORDINATE_SYSTEM_DEVICE) return;

    const float rate = (eng.axis == 0) ? ev->values[0]   // X: Pitch (наклон вперёд/назад)
                       : (eng.axis == 1) ? ev->values[1] // Y: Yaw (поворот влево/вправо)
                       : ev->values[2];                  // Z: Roll (поворот в плоскости экрана)

    // ---- интеграция угла (рад) с НЧ-фильтром ----
    const nsecs_t ts = ev->timestamp;   // та же шкала времени, что SystemClock.elapsedRealtimeNanos()
    if (eng.lastTs != 0) {
        const float dt = static_cast<float>(ts - eng.lastTs) * 1e-9f;
        if (dt > 0.f && dt < kMaxGap) {
            eng.lpfRate  += kLpfAlpha * (rate - eng.lpfRate);
            eng.curAngle += eng.lpfRate * dt;
        } else if (dt >= kMaxGap) {
            // Долгий разрыв (doze/пауза) — перепривязываем якорь, иначе прыжок
            eng.curAngle = eng.startAngle;
            eng.lpfRate  = 0.f;
        }
    }
    eng.lastTs = ts;

    const float offset = eng.curAngle - eng.startAngle;
    if (std::fabsf(offset) < kDeadZoneRad) return;   // джиттер не отправляем

    // ---- маппинг: угол -> t в [0..1] -> позиция между A и B ----
    // t = 0.5 — нейтраль (середина A..B, позиция ACTION_DOWN).
    // Чувствительность 100 -> полный размах A..B при +-2°, чувствительность 1 -> при +-200°.
    const float halfRangeRad = (200.0f / static_cast<float>(eng.sensitivity)) * kDeg2Rad;
    float t = 0.5f + offset / (2.f * halfRangeRad);
    if (t < 0.f) t = 0.f;
    else if (t > 1.f) t = 1.f;

    const float x = eng.x1 + (eng.x2 - eng.x1) * t;
    const float y = eng.y1 + (eng.y2 - eng.y1) * t;

    // Фильтр повторений: только осмысленное движение
    if (std::fabsf(x - eng.lastX) < kMinMovePx && std::fabsf(y - eng.lastY) < kMinMovePx) return;
    eng.lastX = x;
    eng.lastY = y;

    // ---- прямо в Java, из sensor-потока (без очереди/Handler) ----
    JNIEnv *env = AttachEnv();
    if (env == nullptr || eng.callback == nullptr) return;
    env->CallVoidMethod(eng.callback, eng.onSample, x, y, offset * kRad2Deg,
                        static_cast<jlong>(ts));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void TearDownQueue() {
    if (eng.queue != nullptr) {
        ASensorEventQueue_disableSensor(eng.queue, eng.gyro);
        ASensorEventQueue_deleteEventQueue(eng.queue);
        eng.queue = nullptr;
    }
}

}  // namespace

// ============================ JNI interface =================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    eng.jvm = vm;

    eng.mgr = ASensorManager_getInstance();
    if (eng.mgr == nullptr) {
        LOGE("ASensorManager_getInstance() = null");
        return JNI_VERSION_1_6;
    }
    eng.gyro = ASensorManager_getDefaultSensor(eng.mgr, ASENSOR_TYPE_GYROSCOPE);
    if (eng.gyro == nullptr) {
        LOGW("Гирроскоп на устройстве не найден (ASensorManager_getDefaultSensor)");
    } else {
        LOGI("gyro: name=%s, maxRange=%.2f rad/s, resolution=%.5f, minDelay=%d ns",
             ASensor_getName(eng.gyro),
             ASensor_getMaxRange(eng.gyro),
             ASensor_getResolution(eng.gyro),
             ASensor_getMinDelay(eng.gyro));
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    if (eng.callback != nullptr) {
        vm->DeleteGlobalRef(eng.callback);
        eng.callback = nullptr;
    }
    eng.jvm = nullptr;
}

// cb — объект, содержащий onSample(float x, float y, float angleDeg, long sensorTsNs)
JNIEXPORT jboolean JNICALL
Java_com_huitouch_engine_GyroEngine_nativeStart(JNIEnv *env, jobject, jobject cb,
                                                jfloat x1, jfloat y1, jfloat x2, jfloat y2,
                                                jint sensitivity, jint axis) {
    if (eng.gyro == nullptr) {
        LOGE("start: гироскоп недоступен");
        return JNI_FALSE;
    }

    // защита от двойного start
    TearDownQueue();

    // применяем новую конфигурацию
    eng.x1 = x1; eng.y1 = y1;
    eng.x2 = x2; eng.y2 = y2;
    eng.sensitivity = sensitivity < 1 ? 1 : (sensitivity > 100 ? 100 : sensitivity);
    eng.axis = (axis >= 0 && axis <= 2) ? axis : kAxisZ;

    // сброс интегратора: якорь = текущая ориентация устройства
    eng.startAngle = 0.f;
    eng.curAngle   = 0.f;
    eng.lpfRate    = 0.f;
    eng.lastTs     = 0;
    eng.lastX      = -1e9f;
    eng.lastY      = -1e9f;

    if (cb != nullptr) {
        if (eng.callback != nullptr) env->DeleteGlobalRef(eng.callback);
        eng.callback = env->NewGlobalRef(cb);

        const jclass cls = env->GetObjectClass(cb);
        eng.onSample = env->GetMethodID(cls, "onSample", "(FFFL)V");
        if (eng.onSample == nullptr) {
            LOGE("в cb нет onSample(FFFL)V");
            env->ExceptionClear();
            env->DeleteGlobalRef(eng.callback);
            eng.callback = nullptr;
            return JNI_FALSE;
        }
    } else {
        LOGE("start: callback == null");
        return JNI_FALSE;
    }

    eng.queue = ASensorEventQueue_createEventQueue(eng.mgr, OnSensorChanged);
    if (eng.queue == nullptr) {
        LOGE("ASensorEventQueue_createEventQueue failed");
        return JNI_FALSE;
    }
    ASensorEventQueue_enableSensor(eng.queue, eng.gyro);
    // Максимальная доступная частота: 50–200 Гц в зависимости от SoC
    ASensorEventQueue_setDelay(eng.queue, ASENSOR_DELAY_FASTEST);

    eng.running.store(true);
    LOGI("engine started: A=(%.0f,%.0f) B=(%.0f,%.0f) sensitivity=%d axis=%d",
         x1, y1, x2, y2, eng.sensitivity, eng.axis);
    return JNI_TRUE;
}

// Live-перенастройка калибровки во время работы (точки двигают, слайдер крутят)
JNIEXPORT void JNICALL
Java_com_huitouch_engine_GyroEngine_nativeUpdate(JNIEnv *, jobject,
                                                 jfloat x1, jfloat y1, jfloat x2, jfloat y2,
                                                 jint sensitivity, jint axis) {
    eng.x1 = x1; eng.y1 = y1;
    eng.x2 = x2; eng.y2 = y2;
    eng.sensitivity = sensitivity < 1 ? 1 : (sensitivity > 100 ? 100 : sensitivity);
    eng.axis = (axis >= 0 && axis <= 2) ? axis : kAxisZ;
}

JNIEXPORT void JNICALL Java_com_huitouch_engine_GyroEngine_nativeStop(JNIEnv *, jobject) {
    eng.running.store(false);
    TearDownQueue();
    LOGI("engine stopped");
}

}  // extern "C"
