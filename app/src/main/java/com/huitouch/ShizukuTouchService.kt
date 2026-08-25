package com.huitouch

import android.os.IBinder
import android.system.Os
import android.util.Log
import android.view.MotionEvent
import rikka.shizuku.SystemServiceHelper

/**
 * Shizuku USER SERVICE. Это НЕ android.app.Service!
 *
 * Демон Shizuku сам инстансирует этот класс в своём привилегированном
 * процессе (app_process; uid 0 при root, uid 2000 при adb) и передаёт
 * приложению binder на него (см. ShizukuChannel). Отсюда — через
 * IShizukuTouch.injectEvent — события уходят в системный конвейер ввода.
 *
 * Внутри процесса user service НЕТ ограничений non-SDK API, поэтому мы
 * спокойно достаём скрытый InputManager.
 *
 * Задержка на инжект: 1 binder hop (app -> сервис) + 1 reflection-invoke +
 * InputManager.injectInputEvent(ASYNC). Типичные 1–4 мс на современных
 * устройствах.
 */
class ShizukuTouchService : IShizukuTouch.Stub() {

    @Volatile private var initDone = false
    @Volatile private var initError: String? = null

    // Кэшируются один раз: Method + инстанс прокси InputManager
    private var injectMethod: java.lang.reflect.Method? = null
    private var injectTarget: Any? = null

    /** Резервный метод Shizuku-сервера — вызывается перед смертью процесса. */
    override fun destroy() {
        Log.i(TAG, "destroy() -> System.exit(0)")
        System.exit(0)
    }

    override fun getUid(): Int = try {
        Os.getuid().toInt()
    } catch (t: Throwable) {
        -1
    }

    override fun injectEvent(event: MotionEvent, mode: Int): Boolean {
        ensureInjector()
        val m = injectMethod ?: return false
        val t = injectTarget ?: return false
        return try {
            m.invoke(t, event, mode) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(TAG, "inject failed", e)
            false
        }
    }

    /**
     * Разово резолвим скрытый InputManager через рефлексию.
     *
     * Смысл отказа от собственного AIDL-стаба IInputManager:
     * транзакционные коды и точный тип параметра injectInputEvent()
     * РАЗЛИЧАЮТСЯ от версии Android к версии (например, AOSP android-14
     * объявляет параметр как android.view.InputEvent, а более ранние
     * релизы — как android.os.IInputEvent; порядок методов в AOSP-файле
     * тоже меняется). Рефлексия на стабильном скрытом
     * InputManager#injectInputEvent(InputEvent, int) работает на всех
     * релизах и никогда не устареет.
     */
    private fun ensureInjector() {
        if (initDone || initError != null) return
        synchronized(this) {
            if (initDone || initError != null) return
            try {
                // 1) binder системного сервиса "input".
                //    SystemServiceHelper — официальная утилита Shizuku-API:
                //    рефлексия ServiceManager.getService + кэш.
                val binder = SystemServiceHelper.getSystemService("input")
                    ?: throw IllegalStateException("ServiceManager.getService(\"input\") == null")

                // 2) framework-прокси IInputManager
                val stubCls = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubCls.getMethod("asInterface", IBinder::class.java)
                val manager = asInterface.invoke(null, binder)

                // 3) ищем injectInputEvent по имени и аргументам — переживаем
                //    смену типа параметра между релизами.
                //    MotionEvent — подкласс InputEvent И реализует IInputEvent,
                //    поэтому подходит под оба варианта сигнатуры.
                val iface = Class.forName("android.hardware.input.IInputManager")
                val method = iface.methods.firstOrNull {
                    it.name == "injectInputEvent" && it.parameterCount == 2
                } ?: throw NoSuchMethodException("injectInputEvent")

                injectMethod = method
                injectTarget = manager
                initDone = true
                Log.i(TAG, "input injector ready: uid=${getUid()} method=$method")
            } catch (t: Throwable) {
                initError = t.toString()
                Log.e(TAG, "failed to init input injector", t)
            }
        }
    }

    companion object {
        private const val TAG = "ShizukuTouchService"
    }
}
