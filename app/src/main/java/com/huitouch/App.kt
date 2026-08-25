package com.huitouch

import android.app.Application
import android.graphics.Bitmap

class App : Application() {

    companion object {
        /**
         * Изображение, выбранное из галереи.
         * MainActivity и OverlayService живут в одном процессе, поэтому
         * bitmap передаётся статикой, а не через Intent-экстру (слишком большое).
         */
        @Volatile
        @JvmStatic
        var pendingBitmap: Bitmap? = null
    }
}
