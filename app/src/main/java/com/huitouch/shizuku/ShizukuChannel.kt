package com.huitouch.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.huitouch.IShizukuTouch
import rikka.shizuku.Shizuku

/**
 * Держатель живого binder'а нашего Shizuku user service (ShizukuTouchService)
 * и (пере)привязка к нему.
 *
 * Схема (официальная, Shizuku-API v11+):
 *   ShizukuUserService (наш AIDL) выполняется в процессе Shizuku
 *   (app_process, uid 0 для root или 2000 для adb); Shizuku сам запускает
 *   класс — объявлять <service> в манифесте НЕ нужно.
 */
object ShizukuChannel {

    /** Актуальный прокси IShizukuTouch (null = не подключены). */
    @Volatile
    var service: IShizukuTouch? = null
        private set

    /** uid привилегированного процесса (0 = root, 2000 = adb). */
    @Volatile
    var uid: Int = -1

    var onStateChange: ((connected: Boolean, uid: Int) -> Unit)? = null

    private val args = Shizuku.UserServiceArgs(
        // Имя класса передаётся Shizuku строкой — не обфусцировать (см. proguard-rules.pro)
        ComponentName("com.huitouch", "com.huitouch.ShizukuTouchService")
    )
        .daemon(true)            // держать процесс user service, пока панель открыта
        .processNameSuffix("service")
        .debuggable(false)
        .version(1)              // при изменении версии — старому сервису придёт destroy()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = IShizukuTouch.Stub.asInterface(binder)
            service = svc
            uid = try {
                svc.getUid()
            } catch (t: Throwable) {
                -1
            }
            onStateChange?.invoke(true, uid)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            onStateChange?.invoke(false, -1)
            bind()   // авторе-ребаунд (Shizuku перезапустили, процесс умер и т.п.)
        }
    }

    /** Привязаться. Безопасно вызывать в любом состоянии Shizuku. */
    fun bind() {
        try {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            if (service != null) return
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            // Shizuku не запущен / binder мёртв — просто остаёмся не подключёнными
        }
    }

    fun unbind() {
        try {
            Shizuku.unbindUserService(args, true)
        } catch (t: Throwable) {
            // ignore
        }
        service = null
        uid = -1
    }
}
