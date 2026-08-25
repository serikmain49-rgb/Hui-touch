// ============================================================================
//  HUI-TOUCH — нативный гироскоп-движок (NDK)
// ============================================================================

#include <jni.h>
#include <android/log.h>
#include <android/looper.h>
#include <android/sensor.h>

#include <atomic>
#include <cmath>
#include <thread>

#define HT_TAG "HuiTouchNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  HT_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  HT_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HT_TAG, __VA_ARGS__)

namespace {

constexpr float kLpfAlpha    = 0.25f;   // сглаживание шумов гироскопа
constexpr float kMaxGap      = 0.050f;  // перерыв > 50 мс -> сброс
constexpr float kDeadZoneRad = 5.0e-4f; // ~0.03° deadzone
constexpr float kMinMovePx   = 0.6f;    // порог смещения
constexpr float kRad2Deg     = 57.29577951308232f;
constexpr float kDeg2Rad     = 0.017453292519943295f;
constexpr int   kAxisZ       = 2;       // Z (Roll) по умолчанию
constexpr int   kLooperIdent = 1;

struct Engine {
    std::atomic<float> x1{0.f}, y1{0.f}, x2{0.f}, y2{0.f};
    std::atomic<int>   sensitivity{50};
    std::atomic<int>   axis{kAxisZ};

    ASensorManager    *mgr    = nullptr;
    const ASensor     *gyro   = nullptr;

    std::atomic<bool>  running{false};
    ALooper           *looper = nullptr;
    std::thread        workerThread;

    float    startAngle = 0.f;
    float    curAngle   = 0.f;
    float    lpfRate    = 0.f;
    int64_t  lastTs     = 0;

    float    lastX = -1e9f;
    float    lastY = -1e9f;

    JavaVM    *jvm      = nullptr;
    jobject    callback = nullptr;
    jmethodID  onSample = nullptr;
};

Engine eng;

void ProcessSensorEvent(JNIEnv *env, const ASensorEvent &ev) {
    if (ev.type != ASENSOR_TYPE_GYROSCOPE) return;

    const int curAxis = eng.axis.load(std::memory_order_relaxed);
    const float rate = (curAxis == 0) ? ev.data[0]
                     : (curAxis == 1) ? ev.data[1]
                     : ev.data[2];

    const int64_t ts = ev.timestamp;
    if (eng.lastTs != 0) {
        const float dt = static_cast<float>(ts - eng.lastTs) * 1e-9f;
        if (dt > 0.f && dt < kMaxGap) {
            eng.lpfRate  += kLpfAlpha * (rate - eng.lpfRate);
            eng.curAngle += eng.lpfRate * dt;
        } else if (dt >= kMaxGap) {
            eng.curAngle = eng.startAngle;
            eng.lpfRate  = 0.f;
        }
    }
    eng.lastTs = ts;

    const float offset = eng.curAngle - eng.startAngle;
    if (std::fabs(offset) < kDeadZoneRad) return;

    const int sens = eng.sensitivity.load(std::memory_order_relaxed);
    const float halfRangeRad = (200.0f / static_cast<float>(sens)) * kDeg2Rad;
    float t = 0.5f + offset / (2.f * halfRangeRad);
    if (t < 0.f) t = 0.f;
    else if (t > 1.f) t = 1.f;

    const float curX1 = eng.x1.load(std::memory_order_relaxed);
    const float curY1 = eng.y1.load(std::memory_order_relaxed);
    const float curX2 = eng.x2.load(std::memory_order_relaxed);
    const float curY2 = eng.y2.load(std::memory_order_relaxed);

    const float x = curX1 + (curX2 - curX1) * t;
    const float y = curY1 + (curY2 - curY1) * t;

    if (std::fabs(x - eng.lastX) < kMinMovePx && std::fabs(y - eng.lastY) < kMinMovePx) return;
    eng.lastX = x;
    eng.lastY = y;

    if (env != nullptr && eng.callback != nullptr && eng.onSample != nullptr) {
        env->CallVoidMethod(eng.callback, eng.onSample,
                            static_cast<jfloat>(x),
                            static_cast<jfloat>(y),
                            static_cast<jfloat>(offset * kRad2Deg),
                            static_cast<jlong>(ts));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

void SensorThreadFunc() {
    JNIEnv *env = nullptr;
    if (eng.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("SensorThreadFunc: AttachCurrentThread failed");
        return;
    }

    ALooper *looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    if (!looper) {
        LOGE("SensorThreadFunc: ALooper_prepare failed");
        eng.jvm->DetachCurrentThread();
        return;
    }
    eng.looper = looper;

    ASensorEventQueue *queue = ASensorManager_createEventQueue(
        eng.mgr, looper, kLooperIdent, nullptr, nullptr
    );
    if (!queue) {
        LOGE("SensorThreadFunc: ASensorManager_createEventQueue failed");
        eng.looper = nullptr;
        eng.jvm->DetachCurrentThread();
        return;
    }

    ASensorEventQueue_enableSensor(queue, eng.gyro);
    int minDelayUs = ASensor_getMinDelay(eng.gyro);
    if (minDelayUs <= 0) minDelayUs = 10000;
    ASensorEventQueue_setEventRate(queue, eng.gyro, minDelayUs);

    constexpr int kBatchSize = 8;
    ASensorEvent events[kBatchSize];

    while (eng.running.load(std::memory_order_relaxed)) {
        int ident = ALooper_pollOnce(100, nullptr, nullptr, nullptr);
        if (ident == kLooperIdent) {
            ssize_t numEvents;
            while ((numEvents = ASensorEventQueue_getEvents(queue, events, kBatchSize)) > 0) {
                for (ssize_t i = 0; i < numEvents; ++i) {
                    ProcessSensorEvent(env, events[i]);
                }
            }
        }
    }

    ASensorEventQueue_disableSensor(queue, eng.gyro);
    ASensorManager_destroyEventQueue(eng.mgr, queue);
    eng.looper = nullptr;

    eng.jvm->DetachCurrentThread();
    LOGI("SensorThreadFunc: exited cleanly");
}

void StopWorker() {
    eng.running.store(false);
    if (eng.looper != nullptr) {
        ALooper_wake(eng.looper);
    }
    if (eng.workerThread.joinable()) {
        eng.workerThread.join();
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    eng.jvm = vm;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    eng.mgr = ASensorManager_getInstanceForPackage("com.huitouch");
    if (eng.mgr == nullptr) {
        eng.mgr = ASensorManager_getInstance();
    }
#pragma clang diagnostic pop

    if (eng.mgr == nullptr) {
        LOGE("ASensorManager_getInstance() = null");
        return JNI_VERSION_1_6;
    }
    eng.gyro = ASensorManager_getDefaultSensor(eng.mgr, ASENSOR_TYPE_GYROSCOPE);
    if (eng.gyro == nullptr) {
        LOGW("Гироскоп на устройстве не найден");
    } else {
        LOGI("gyro: name=%s, vendor=%s, resolution=%.5f, minDelay=%d us",
             ASensor_getName(eng.gyro),
             ASensor_getVendor(eng.gyro),
             ASensor_getResolution(eng.gyro),
             ASensor_getMinDelay(eng.gyro));
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    StopWorker();
    if (eng.callback != nullptr) {
        JNIEnv *env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(eng.callback);
        }
        eng.callback = nullptr;
    }
    eng.jvm = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_huitouch_engine_GyroEngine_nativeStart(JNIEnv *env, jobject, jobject cb,
                                                jfloat x1, jfloat y1, jfloat x2, jfloat y2,
                                                jint sensitivity, jint axis) {
    if (eng.gyro == nullptr) {
        LOGE("start: гироскоп недоступен");
        return JNI_FALSE;
    }

    StopWorker();

    eng.x1.store(x1);
    eng.y1.store(y1);
    eng.x2.store(x2);
    eng.y2.store(y2);
    eng.sensitivity.store(sensitivity < 1 ? 1 : (sensitivity > 100 ? 100 : sensitivity));
    eng.axis.store((axis >= 0 && axis <= 2) ? axis : kAxisZ);

    eng.startAngle = 0.f;
    eng.curAngle   = 0.f;
    eng.lpfRate    = 0.f;
    eng.lastTs     = 0;
    eng.lastX      = -1e9f;
    eng.lastY      = -1e9f;

    if (cb != nullptr) {
        if (eng.callback != nullptr) {
            env->DeleteGlobalRef(eng.callback);
            eng.callback = nullptr;
        }
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

    eng.running.store(true);
    eng.workerThread = std::thread(SensorThreadFunc);

    LOGI("engine started: A=(%.0f,%.0f) B=(%.0f,%.0f) sensitivity=%d axis=%d",
         x1, y1, x2, y2, eng.sensitivity.load(), eng.axis.load());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_huitouch_engine_GyroEngine_nativeUpdate(JNIEnv *, jobject,
                                                 jfloat x1, jfloat y1, jfloat x2, jfloat y2,
                                                 jint sensitivity, jint axis) {
    eng.x1.store(x1);
    eng.y1.store(y1);
    eng.x2.store(x2);
    eng.y2.store(y2);
    eng.sensitivity.store(sensitivity < 1 ? 1 : (sensitivity > 100 ? 100 : sensitivity));
    eng.axis.store((axis >= 0 && axis <= 2) ? axis : kAxisZ);
}

JNIEXPORT void JNICALL Java_com_huitouch_engine_GyroEngine_nativeStop(JNIEnv *, jobject) {
    StopWorker();
    LOGI("engine stopped");
}

}  // extern "C"
