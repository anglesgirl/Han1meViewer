package com.yenaly.han1meviewer.logic.network.ech

object EchTrace {
    @JvmField val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @JvmStatic fun event(message: String) { events.add(message) }
}

object ConscryptEch {
    @JvmField var clearCalls: Int = 0
    @JvmStatic fun clearEchUnavailable() { clearCalls++ }
}
