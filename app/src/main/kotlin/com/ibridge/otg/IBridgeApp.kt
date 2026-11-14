package com.ibridge.otg

import android.app.Application
import com.ibridge.otg.core.AppController

class IBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppController.get(this)
    }
}
