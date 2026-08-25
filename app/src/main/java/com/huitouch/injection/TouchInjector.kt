package com.huitouch.injection

import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputManager
import android.view.MotionEvent
import com.huitouch.shizuku.ShizukuChannel

/**
 * Формирует кадры MotionEvent и прокидывает их через Shizuku user service
 * в системный конвейер ввода.
 *
 * Жизненный цикл "касания" (одна логическая палец-сессия):
 *   startDrag() -> ACTION_DOWN в нейтральной точке (середина A..B)
 *   onSample()  -> непрерывный поток ACTION_MOVE, пока наклоняют устройство
 *   endDrag()   -> ACTION_UP в последней позиции
 *
 * Всё — в режиме INJECT_INPUT_EVENT_MODE_ASYNC (0): вызов не блокируется
 * ожиданием доставки, событие сразу уходит в InputDispatcher.
 */
object TouchInjector {

    private const val TAG = "TouchInjector"
    private const val PRESSURE = 0.35f   // типовое давление пальца, постоянное — нормально

    @Volatile private var downTime: Long = 0L
    @Volatile private var dragging: Boolean = false

    // ---- встроенный замер задержки: timestamp сенсора -> вызов injectEvent ----
    private val latBuf = LongArray(128)   // кольцевой буфер, ns
    private var latHead = 0
    @Volatile
    var avgLatencyMs: Double = -1.0
        private set

    fun startDrag(x: Double, y: Double) {
        val t = SystemClock.uptimeMillis()
        downTime = t
        dragging = true
        inject(MotionEvent.ACTION_DOWN, x, y, t)
    }

    /** Вызывается с частотой гироскопа (до 200 Гц) из native sensor thread. */
    fun onSample(x: Double, y: Double, sensorTsNs: Long) {
        if (!dragging) return
        recordLatency(sensorTsNs)
        inject(MotionEvent.ACTION_MOVE, x, y, downTime)
    }

    fun endDrag(x: Double, y: Double) {
        if (!dragging) return
        dragging = false
        inject(MotionEvent.ACTION_UP, x, y, downTime)
    }

    private fun recordLatency(sensorTsNs: Long) {
        // ev->timestamp и SystemClock.elapsedRealtimeNanos() — одна шкала времени
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs <= sensorTsNs) return
        latBuf[latHead % latBuf.size] = nowNs - sensorTsNs
        latHead++
        val n = minOf(latHead, latBuf.size)
        var sum = 0L
        for (i in 0 until n) sum += latBuf[i]
        avgLatencyMs = sum.toDouble() / n / 1e6
    }

    private fun inject(action: Int, x: Double, y: Double, dt: Long) {
        val svc = ShizukuChannel.service ?: return
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(dt, now, action, x.toFloat(), y.toFloat(), PRESSURE)
        try {
            // 0 == InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
            svc.injectEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (e: RemoteException) {
            Log.w(TAG, "injectEvent failed (Shizuku service died?)", e)
        } finally {
            ev.recycle()
        }
    }
}
