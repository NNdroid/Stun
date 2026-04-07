package app.fjj.stun

import android.app.Application
import app.fjj.stun.repo.StunLogger
import java.io.File

class StunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize StunLogger in cache directory
        val logFile = File(cacheDir, "stun.log")
        StunLogger.init(logFile, append = true)
        
        // Setup bridge to UI LiveData
        app.fjj.stun.repo.StunRepository.setupLogBridge()
        
        StunLogger.i("StunApp", "Application initialized and StunLogger started.")
    }

    override fun onTerminate() {
        StunLogger.release()
        super.onTerminate()
    }
}
