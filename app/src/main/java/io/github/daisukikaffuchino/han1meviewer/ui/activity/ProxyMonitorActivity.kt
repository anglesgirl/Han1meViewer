package io.github.daisukikaffuchino.han1meviewer.ui.activity

import android.os.Bundle
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.ProxyMonitorScreen

class ProxyMonitorActivity : BaseActivity() {
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setHanimeContent {
            ProxyMonitorScreen(
                onBack = { finish() },
            )
        }
    }
}
