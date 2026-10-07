package com.songaudit

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.songaudit.ui.AuditApp
import com.songaudit.ui.AuditViewModel
import com.songaudit.ui.theme.SongAuditTheme

class MainActivity : ComponentActivity() {

    private val vm: AuditViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app has one scheme and it is light. Left on auto, a phone in dark
        // mode would draw white status icons onto the paper.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            SongAuditTheme {
                AuditApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the "all files access" settings page.
        vm.refreshAccess()
    }
}
