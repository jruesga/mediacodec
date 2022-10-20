package uk.sky.brownbags.mediacodec

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

abstract class InmersiveActivity : AppCompatActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                            or View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }
}