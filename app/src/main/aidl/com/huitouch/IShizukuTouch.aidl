package com.huitouch;

import android.view.MotionEvent;

/**
 * Интерфейс Shizuku USER SERVICE.
 *
 * Приложение получает прокси этого интерфейса в
 * ServiceConnection.onServiceConnected (binder, см. ShizukuChannel) и передаёт
 * в сервис уже собранные MotionEvent. Сервис (com.huitouch.ShizukuTouchService)
 * выполняется в привилегированном процессе Shizuku (uid 0/2000), где нет
 * ограничений non-SDK API, и прокидывает событие в системный конвейер ввода
 * через скрытый InputManager#injectInputEvent.
 *
 * Один injectEvent() = один binder hop в привилегированный процесс +
 * InputManager.injectInputEvent(..., MODE_ASYNC) — событие попадает в
 * InputDispatcher без ожидания нашего возврата.
 */
interface IShizukuTouch {

    /**
     * Резервный метод Shizuku-сервера — ОБЯЗАТЕЛЕН в каждом user service.
     * Вызывается перед убийством процесса (код транзакции зафиксирован).
     */
    void destroy() = 16777114;

    /**
     * Инжект события в системный конвейер ввода.
     *
     * @param event MotionEvent (ACTION_DOWN/MOVE/UP), собранный приложением
     * @param mode  0 == InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (неблокирующе)
     * @return true, если событие принято InputManager
     */
    boolean injectEvent(in MotionEvent event, int mode) = 1;

    /** Диагностика: uid процесса-хоста (0 = root, 2000 = adb/shell). */
    int getUid() = 2;
}
