package app.fjj.stun.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.util.LocaleHelper

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }
}
