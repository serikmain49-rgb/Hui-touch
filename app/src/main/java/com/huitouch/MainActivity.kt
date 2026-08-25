package com.huitouch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.huitouch.shizuku.ShizukuChannel
import rikka.shizuku.Shizuku

/**
 * Точка входа: авторизация Shizuku, разрешения (overlay/уведомления),
 * запуск панели и «мост» для флоров, требующих Activity
 * (согласие на скриншот MediaProjection, выбор картинки из галереи).
 */
class MainActivity : Activity() {

    private var tvShizuku: TextView? = null
    private var tvOverlay: TextView? = null
    private var tvHint: TextView? = null

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, granted ->
        runOnUiThread {
            if (granted) ShizukuChannel.bind()
            refresh()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        ShizukuChannel.bind()
        runOnUiThread { refresh() }
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvShizuku = findViewById(R.id.tv_shizuku)
        tvOverlay = findViewById(R.id.tv_overlay)
        tvHint = findViewById(R.id.tv_hint)

        findViewById<Button>(R.id.btn_shizuku).setOnClickListener { requestShizukuPermission() }
        findViewById<Button>(R.id.btn_open).setOnClickListener {
            if (ensureOverlayPermission()) {
                startService(Intent(this, OverlayService::class.java))
            }
        }
        findViewById<Button>(R.id.btn_capture).setOnClickListener {
            if (ensureOverlayPermission()) {
                startService(Intent(this, OverlayService::class.java))
                requestCapture()
            }
        }
        findViewById<Button>(R.id.btn_gallery).setOnClickListener { requestGallery() }

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        requestNotificationsPermission()

        // намерения из плавающей панели (Service сам не может запустить Activity-флоу)
        when (intent?.action) {
            OverlayService.ACTION_REQUEST_CAPTURE -> requestCapture()
            OverlayService.ACTION_REQUEST_GALLERY -> requestGallery()
        }

        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }

    /**
     * Activity уже жила в бэкенде (напр. вызов с панели при CLEAR_TOP) —
     * намерения приходят сюда, а не в onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            OverlayService.ACTION_REQUEST_CAPTURE -> requestCapture()
            OverlayService.ACTION_REQUEST_GALLERY -> requestGallery()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_CAPTURE -> {
                val i = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_CAPTURE)
                    .putExtra(OverlayService.EXTRA_RC, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startService(i)
            }
            REQ_GALLERY -> {
                val uri = data.data ?: return
                val bmp = loadBitmap(uri) ?: run {
                    tvHint?.text = "Не удалось прочитать изображение"
                    return
                }
                App.pendingBitmap = bmp
                startService(
                    Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_SET_IMAGE)
                )
            }
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun requestGallery() {
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(
            Intent.createChooser(pick, "Выберите скриншот игры"),
            REQ_GALLERY
        )
    }

    /** Двухпроходный декод с даунскейлом — не раздуваем память. */
    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        tvHint?.text = "Разрешите «рисование поверх других приложений», иначе панель не появится"
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            tvHint?.text = "Не удалось открыть настройки overlay-разрешения"
        }
        return false
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun requestShizukuPermission() {
        try {
            when {
                Shizuku.isPreV11() -> tvHint?.text = "Требуется Shizuku версии 11 или новее"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> return
                Shizuku.shouldShowRequestPermissionRationale() ->
                    tvHint?.text = "Shizuku: доступ ранее отклонён — разрешите в настройках Shizuku"
                else -> Shizuku.requestPermission(1001)
            }
        } catch (t: Throwable) {
            tvHint?.text = "Shizuku не запущен: запустите приложение Shizuku (QR/ADB/root) и повторите"
        }
    }

    private fun refresh() {
        if (isFinishing) return
        val sb = StringBuilder()
        try {
            when {
                Shizuku.isPreV11() -> sb.append("Shizuku: требуется версия 11+")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    sb.append("Shizuku: авторизован (uid ${Shizuku.getUid()}, API v${Shizuku.getVersion()})")
                else -> sb.append("Shizuku: нужен доступ — нажмите кнопку выше")
            }
        } catch (t: Throwable) {
            sb.append("Shizuku: не запущен — запустите его на устройстве")
        }
        tvShizuku?.text = sb.toString()
        tvOverlay?.text = if (Settings.canDrawOverlays(this)) {
            "Окно поверх приложений: разрешено"
        } else {
            "Окно поверх приложений: нет разрешения"
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_GALLERY = 1002
    }
}
