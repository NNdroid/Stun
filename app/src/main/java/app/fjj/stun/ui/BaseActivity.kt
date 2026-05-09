package app.fjj.stun.ui

import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {
    // AppCompatDelegate.setApplicationLocales handles context wrapping for us.
    // Manual wrapping in attachBaseContext can conflict with modern per-app language settings.
}
