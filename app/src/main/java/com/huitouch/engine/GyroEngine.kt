package com.huitouch.engine

import android.util.Log
import com.huitouch.injection.TouchInjector

/**
 * Java-обёртка над C++ движком (cpp/native-lib.cpp).
 *
 * Модель потоков — весь смысл NDK-части:
 *   ASensorEventQueue (sensor-поток, до 200 Гц)
 *     -> интеграция угла + маппинг A..B (C++, без аллокаций)
 *     -> прямой JNI-вызов onSample (БЕЗ Handler/Looper hop)
 *     -> TouchInjector -> binder Shizuku -> InputManager.injectInputEvent(ASYNC)
 */
object GyroEngine {

    private const val TAG = "GyroEngine"

    @Volatile
    var active: Boolean = false
        private set

    fun start(x1: Float, y1: Float, x2: Float, y2: Float, sensitivity: Int, axis: Int): Boolean {
        if (active) return true
        val ok = nativeStart(this, x1, y1, x2, y2, sensitivity, axis)
        if (ok) {
            active = true
        } else {
            Log.e(TAG, "nativeStart failed (нет гироскопа?)")
        }
        return ok
    }

    /** Live-перенастройка во время работы (точки/слайдер/ось изменены). */
    fun update(x1: Float, y1: Float, x2: Float, y2: Float, sensitivity: Int, axis: Int) {
        if (active) nativeUpdate(x1, y1, x2, y2, sensitivity, axis)
    }

    fun stop() {
        if (!active) return
        active = false
        nativeStop()
    }

    // ================= JNI callback: вызывается из NATIVE SENSOR THREAD =================
    fun onSample(x: Float, y: Float, angleDeg: Float, sensorTsNs: Long) {
        if (!active) return
        // Сразу в конвейер инжекции — никакого хопана на главный поток.
        TouchInjector.onSample(x.toDouble(), y.toDouble(), sensorTsNs)
    }

    private external fun nativeStart(
        cb: Any,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        sensitivity: Int, axis: Int
    ): Boolean

    private external fun nativeUpdate(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        sensitivity: Int, axis: Int
    )

    private external fun nativeStop()

    init {
        System.loadLibrary("huitouch")
    }
}
