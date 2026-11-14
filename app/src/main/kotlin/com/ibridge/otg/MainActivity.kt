package com.ibridge.otg

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ibridge.otg.core.AppController
import com.ibridge.otg.ui.IBridgeRoot

class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AppController.get(this)
        setContent {
            IBridgeRoot(controller)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        controller.usb.refresh()
        controller.refreshDevice()
    }

    override fun onDestroy() {
        if (isFinishing) {
            controller.stopDaemonOnExitIfNeeded()
        }
        super.onDestroy()
    }
}
