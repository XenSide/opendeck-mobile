package dev.opendeck.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import dev.opendeck.mobile.ui.App

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(DuotoneBlue.pri),
                    onPrimary = Color(DuotoneBlue.bg),
                    secondary = Color(DuotoneBlue.sec),
                    background = Color(DuotoneBlue.bg),
                    surface = Color(DuotoneBlue.bg),
                    surfaceVariant = Color(DuotoneBlue.sec),
                ),
            ) {
                App(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppStart()
    }

    override fun onStop() {
        vm.onAppStop()
        super.onStop()
    }
}
