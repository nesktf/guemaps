package com.nesktf.guemaps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nesktf.guemaps.ui.navigation.AppNavigation
import com.nesktf.guemaps.ui.theme.GüemapsTheme

class MainActivity : ComponentActivity() {

    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntentData(intent)
        setContent {
            GüemapsTheme {
                AppNavigation(
                    incomingUri = incomingUri,
                    onIncomingUriConsumed = { incomingUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentData(intent)
    }

    private fun handleIntentData(intent: Intent?) {
        val data = intent?.data
        if (data != null) {
            val isCustomScheme = data.scheme.equals("guemaps", ignoreCase = true)
            val isWebScheme = (data.scheme.equals("https", ignoreCase = true) || data.scheme.equals("http", ignoreCase = true)) &&
                    data.host.equals("guemaps.app", ignoreCase = true)
            if (isCustomScheme || isWebScheme) {
                incomingUri = data
            }
        }
    }
}