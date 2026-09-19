package io.guise.app

import android.app.Application
import io.guise.app.service.XposedBridgeClient

class GuiseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before any repository tries to read remote preferences.
        XposedBridgeClient.init()
    }
}
