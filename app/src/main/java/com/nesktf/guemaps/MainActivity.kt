package com.nesktf.guemaps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nesktf.guemaps.ui.navigation.AppNavigation
import com.nesktf.guemaps.ui.theme.GüemapsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GüemapsTheme {
                AppNavigation()
            }
        }
    }
}