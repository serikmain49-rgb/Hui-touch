package com.huitouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.huitouch.engine.GyroEngine
import com.huitouch.injection.TouchInjector
import com.huitouch.shizuku.ShizukuChannel
import com.huitouch.ui.ScreenshotView
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Foreground-сервис приложения. Владеет:
 *  1. плавающей панелью калибровки (скриншот + точки A/B + чувствительность + ось),
 *  2. мини-пилюлёй (быстрый стоп/вызов панели поверх игры),
 *  3. скриншотами через MediaProjection,
 *  4. жизненным циклом эмуляции: C++ гироскоп-движок + инжекция через Shizuku.
 *
 * Важно: панель ставится TYPE_APPLICATION_OVERLAY и НЕ ЗАБИРАЕТ фокус
 * (FLAG_NOT_FOCUSABLE), а в момент активной эмуляции становится ещё и
 * FLAG_NOT_TOUCHABLE, чтобы не перехватывала инжектированные касания.
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null

    // --- окна ---
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var pill: View? = null
    private var pillLp: WindowManager.LayoutParams? = null

    // --- виджеты панели ---
    private var screenshot: ScreenshotView? = null
    private var tvResolution: TextView? = null
    private var tvPoints: TextView? = null
    private var tvShizuku: TextView? = null
    private var tvStatus: TextView? = null
    private var tvSens: TextView? = null
    private var seek: SeekBar? = null
    private var btnAxisX: Button? = null
    private var btnAxisY: Button? = null
    private var btnAxisZ: Button? = null

    // --- скриншоты ---
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // --- калибровка (в пикселях ЭКРАНА — в тех координатах, которые видит игра) ---
    private var ax = 0f; private var ay = 0f
    private var bx = 0f; private var by = 0f
    private var sensitivity = 50          // 1..100
    private var axis = 2                  // 0 = X (Pitch), 1 = Y (Yaw), 2 = Z (Roll) — по умолчанию

    @Volatile private var emuRunning = false
    private var lastX = 0.0
    private var lastY = 0.0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    private val binderListener = Shizuku.OnBinderReceivedListener {
        ShizukuChannel.bind()
        mainHandler.post { refreshShizukuStatus() }
    }

    private val latencyTicker = object : Runnable {
        override fun run() {
            if (!emuRunning) return
            val lat = TouchInjector.avgLatencyMs
            if (lat > 0) {
                status(
                    "● Активен · ось ${axisName()} · чувств. $sensitivity · " +
                        "~${"%.1f".format(Locale.US, lat)} мс (датчик→инжект)"
                )
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    // ============================== lifecycle ==============================

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ShizukuChannel.onStateChange = { _, _ -> mainHandler.post { refreshShizukuStatus() } }
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        ShizukuChannel.bind()
        startForeground(NOTIF_ID, buildNotification("Панель HUI-TOUCH"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> {
                val rc = intent.getIntExtra(EXTRA_RC, -1)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (rc != -1 && data != null) captureScreen(rc, data)
                else status("Не получен результат разрешения на скриншот")
            }
            ACTION_SET_IMAGE -> {
                val bmp = App.pendingBitmap
                App.pendingBitmap = null
                if (bmp != null) {
                    showPanel()
                    screenshot?.setBitmap(bmp)
                    status("Изображение загружено — отметьте точки A и B")
                }
            }
            ACTION_START -> startEmu()
            ACTION_STOP -> stopEmu()
            ACTION_HIDE -> hidePanel()
            ACTION_SHOW -> showPanel()
            else -> showPanel()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // стоп без UI-побочных эффектов (не добавляем окна на этапе destroy)
        if (emuRunning) {
            emuRunning = false
            TouchInjector.endDrag(lastX, lastY)
            GyroEngine.stop()
        }
        mainHandler.removeCallbacks(latencyTicker)
        Shizuku.removeBinderReceivedListener(binderListener)
        ShizukuChannel.onStateChange = null
        ShizukuChannel.unbind()
        releaseCapture()
        panel?.let { runCatching { wm?.removeView(it) } }
        pill?.let { runCatching { wm?.removeView(it) } }
        panel = null; pill = null
        super.onDestroy()
    }

    // ============================== панель ================================

    private fun showPanel() {
        if (panel != null) return
        hidePill()

        val root = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        wirePanel(root)

        val lp = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (8 * density).toInt()
        lp.y = statusBarHeight() + (8 * density).toInt()

        wm?.addView(root, lp)
        panel = root
        panelLp = lp
        tvResolution?.text = "${screenW}×${screenH}"
        refreshShizukuStatus()
        if (emuRunning) setPanelTouchable(false)
    }

    private fun wirePanel(root: View) {
        screenshot = root.findViewById(R.id.screenshot)
        tvResolution = root.findViewById(R.id.tv_resolution)
        tvPoints = root.findViewById(R.id.tv_points)
        tvShizuku = root.findViewById(R.id.tv_shizuku)
        tvStatus = root.findViewById(R.id.tv_status)
        tvSens = root.findViewById(R.id.tv_sens)
        seek = root.findViewById(R.id.seek_sens)
        btnAxisX = root.findViewById(R.id.btn_axis_x)
        btnAxisY = root.findViewById(R.id.btn_axis_y)
        btnAxisZ = root.findViewById(R.id.btn_axis_z)

        // --- перетаскивание окна за заголовок ---
        val header = root.findViewById<View>(R.id.header)
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        header.setOnTouchListener { _, e ->
            val l = panelLp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    originX = l.x; originY = l.y
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    l.x = originX + (e.rawX - downX).toInt()
                    l.y = (originY + (e.rawY - downY).toInt()).coerceAtLeast(0)
                    panel?.let { runCatching { wm?.updateViewLayout(it, l) } }
                    true
                }
                else -> false
            }
        }

        // --- точки A/B: нормированные координаты -> пиксели экрана ---
        screenshot?.onPointsChanged = { nx1, ny1, nx2, ny2 ->
            ax = (nx1 * screenW).toInt().toFloat()
            ay = (ny1 * screenH).toInt().toFloat()
            bx = (nx2 * screenW).toInt().toFloat()
            by = (ny2 * screenH).toInt().toFloat()
            tvPoints?.text = "A(${ax.toInt()}, ${ay.toInt()}) → B(${bx.toInt()}, ${by.toInt()})"
            if (GyroEngine.active) GyroEngine.update(ax, ay, bx, by, sensitivity, axis)
        }

        // --- чувствительность 1..100 ---
        seek?.apply {
            min = 1
            max = 100
            progress = sensitivity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    sensitivity = p
                    tvSens?.text = p.toString()
                    if (GyroEngine.active) GyroEngine.update(ax, ay, bx, by, sensitivity, axis)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        // --- ось гироскопа ---
        fun selectAxis(a: Int) {
            axis = a
            btnAxisX?.isSelected = a == 0
            btnAxisY?.isSelected = a == 1
            btnAxisZ?.isSelected = a == 2
            if (GyroEngine.active) GyroEngine.update(ax, ay, bx, by, sensitivity, axis)
        }
        btnAxisX?.setOnClickListener { selectAxis(0) }
        btnAxisY?.setOnClickListener { selectAxis(1) }
        btnAxisZ?.setOnClickListener { selectAxis(2) }
        selectAxis(axis)

        // --- источники изображения (через MainActivity — нужен Activity) ---
        root.findViewById<Button>(R.id.btn_shot).setOnClickListener {
            openMainActivity(ACTION_REQUEST_CAPTURE)
        }
        root.findViewById<Button>(R.id.btn_gallery).setOnClickListener {
            openMainActivity(ACTION_REQUEST_GALLERY)
        }

        // --- управление ---
        root.findViewById<Button>(R.id.btn_start).setOnClickListener { startEmu() }
        root.findViewById<Button>(R.id.btn_stop).setOnClickListener { stopEmu() }
        root.findViewById<Button>(R.id.btn_hide).setOnClickListener { hidePanel() }
        root.findViewById<Button>(R.id.btn_close).setOnClickListener { stopSelf() }

        refreshShizukuStatus()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm?.removeView(it) } }
        panel = null
        panelLp = null
        showPill()
    }

    private fun openMainActivity(action: String) {
        val i = Intent(this, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
    }

    // ============================== пилюля ================================

    private fun showPill() {
        if (pill != null) return
        val t = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(
                (18 * density).toInt(), (10 * density).toInt(),
                (18 * density).toInt(), (10 * density).toInt()
            )
            contentDescription = "HUI-TOUCH: стоп/панель"
        }
        val bg = GradientDrawable().apply {
            cornerRadius = 48f * density
        }
        t.background = bg

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = (12 * density).toInt()
        lp.y = (24 * density).toInt()

        t.setOnClickListener {
            if (emuRunning) stopEmu() else { hidePill(); showPanel() }
        }
        t.setOnLongClickListener {
            hidePill(); showPanel()
            true
        }
        wm?.addView(t, lp)
        pill = t
        pillLp = lp
        refreshPillUi()
    }

    private fun refreshPillUi() {
        val t = pill as? TextView ?: return
        val bg = t.background as? GradientDrawable ?: return
        t.text = if (emuRunning) "⏹" else "🎛"
        bg.setColor(if (emuRunning) 0xDDD32F2F.toInt() else 0xDD424242.toInt())
    }

    private fun hidePill() {
        pill?.let { runCatching { wm?.removeView(it) } }
        pill = null
        pillLp = null
    }

    // ========================= эмуляция: старт/стоп =========================

    private fun startEmu() {
        if (emuRunning) return
        val svc = ShizukuChannel.service
        if (svc == null || !ping(svc)) {
            status("Shizuku не подключён: откройте приложение и авторизуйте HUI-TOUCH")
            return
        }
        if (!GyroEngine.start(ax, ay, bx, by, sensitivity, axis)) {
            status("Ошибка: гироскоп недоступен на этом устройстве")
            return
        }
        // "Палец" появляется в нейтральной позиции — середине отрезка A..B
        lastX = (ax + bx) / 2.0
        lastY = (ay + by) / 2.0
        TouchInjector.startDrag(lastX, lastY)

        emuRunning = true
        setPanelTouchable(false)          // панель не должна перехватывать инжект
        hidePanel()                        // освобождаем экран для игры
        mainHandler.removeCallbacks(latencyTicker)
        mainHandler.post(latencyTicker)
        notifyStatus()
        refreshPillUi()
    }

    private fun stopEmu() {
        if (!emuRunning) return
        emuRunning = false
        mainHandler.removeCallbacks(latencyTicker)
        TouchInjector.endDrag(lastX, lastY)
        GyroEngine.stop()
        setPanelTouchable(true)
        showPanel()
        status("Остановлен")
        notifyStatus()
        refreshPillUi()
    }

    private fun ping(svc: IShizukuTouch): Boolean = try {
        svc.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun setPanelTouchable(touchable: Boolean) {
        val lp = panelLp ?: return
        if (touchable) lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        panel?.let { runCatching { wm?.updateViewLayout(it, lp) } }
    }

    private fun axisName(): String = when (axis) {
        0 -> "X (Pitch)"
        1 -> "Y (Yaw)"
        else -> "Z (Roll)"
    }

    // ============================= скриншоты ===============================

    private fun captureScreen(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)

            releaseVirtualDisplayOnly()
            val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bmp = try {
                    imageToBitmap(img)
                } finally {
                    img.close()
                }
                if (bmp != null) mainHandler.post {
                    showPanel()
                    screenshot?.setBitmap(bmp)
                }
            }, mainHandler)
            imageReader = reader

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "HuiTouch",
                screenW, screenH,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            status("Скриншот получен — отметьте точки A и B")
        } catch (t: Throwable) {
            status("Скриншот: ${t.message}")
        }
    }

    private fun releaseVirtualDisplayOnly() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
    }

    private fun releaseCapture() {
        releaseVirtualDisplayOnly()
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
    }

    /** Копирование кадра ImageReader в Bitmap с учётом стридов (RGBA_8888). */
    private fun imageToBitmap(img: Image): Bitmap? {
        return try {
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (rowStride == w * 4 && pixelStride == 4) {
                // тесная упаковка — быстрый путь
                bmp.copyPixelsFromBuffer(buf)
            } else {
                // путь с произвольными стриды (подгоночные буферы)
                val px = IntArray(w * h)
                var out = 0
                for (row in 0 until h) {
                    var off = row * rowStride
                    for (col in 0 until w) {
                        val r = buf.get(off).toInt() and 0xFF
                        val g = buf.get(off + 1).toInt() and 0xFF
                        val b = buf.get(off + 2).toInt() and 0xFF
                        val a = buf.get(off + 3).toInt() and 0xFF
                        px[out++] = (a shl 24) or (b shl 16) or (g shl 8) or r
                        off += pixelStride
                    }
                }
                bmp.setPixels(px, 0, w, 0, 0, w, h)
            }
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    // ============================ UI / уведомления =========================

    private fun status(s: String) {
        mainHandler.post { tvStatus?.text = s }
    }

    private fun refreshShizukuStatus() {
        val svc = ShizukuChannel.service
        tvShizuku?.text = if (svc != null) {
            "Shizuku: подключён (uid ${ShizukuChannel.uid})"
        } else {
            "Shizuku: не подключён"
        }
    }

    private fun notifyStatus() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                buildNotification(if (emuRunning) "Эмуляция активна" else "Панель HUI-TOUCH")
            )
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "HUI-TOUCH", NotificationManager.IMPORTANCE_LOW)
            )
        }
        fun pi(code: Int, action: String?): PendingIntent {
            val i = Intent(this, OverlayService::class.java).apply {
                if (action != null) setAction(action)
            }
            return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("HUI-TOUCH")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Стоп", pi(1, ACTION_STOP))
            .addAction(0, "Скрыть", pi(2, ACTION_HIDE))
            .addAction(0, "Панель", pi(3, ACTION_SHOW))
            .build()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * density).toInt()
    }

    companion object {
        const val ACTION_CAPTURE = "com.huitouch.ACTION_CAPTURE"
        const val ACTION_SET_IMAGE = "com.huitouch.ACTION_SET_IMAGE"
        const val ACTION_START = "com.huitouch.ACTION_START"
        const val ACTION_STOP = "com.huitouch.ACTION_STOP"
        const val ACTION_HIDE = "com.huitouch.ACTION_HIDE"
        const val ACTION_SHOW = "com.huitouch.ACTION_SHOW"
        const val ACTION_REQUEST_CAPTURE = "com.huitouch.REQUEST_CAPTURE"
        const val ACTION_REQUEST_GALLERY = "com.huitouch.REQUEST_GALLERY"

        const val EXTRA_RC = "rc"
        const val EXTRA_DATA = "data"

        private const val NOTIF_ID = 42
        private const val CH_ID = "huitouch"
    }
}
