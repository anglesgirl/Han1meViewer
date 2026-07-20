package io.github.daisukikaffuchino.han1meviewer.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.daisukikaffuchino.han1meviewer.ui.screen.login.ManualInputCookiesScreen
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeTheme

class ManualInputCookiesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HanimeTheme {
                ManualInputCookiesScreen(
                    onBack = { finish() },
                    onCookieScanned = { scannedCookie ->
                        val resultIntent = Intent().apply {
                            putExtra("cookie", scannedCookie)
                            Log.i("LoginActivity", scannedCookie)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    },
                )
            }
        }
    }

}
