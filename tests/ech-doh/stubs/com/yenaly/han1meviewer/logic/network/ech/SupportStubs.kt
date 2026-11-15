package com.yenaly.han1meviewer.logic.network.ech

object EchTrace {
    @JvmField val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @JvmStatic fun event(message: String) { events.add(message) }
}

object ConscryptEch {
    @JvmField var clearCalls: Int = 0
    @JvmField val clearedHosts: MutableList<String> = mutableListOf()
    @JvmStatic fun clearEchUnavailable() { clearCalls++ }
    @JvmStatic fun clearConfiguredEchHosts() { clearedHosts.clear() }
    @JvmStatic fun clearConfiguredEchHost(host: String) { clearedHosts += host }
}
