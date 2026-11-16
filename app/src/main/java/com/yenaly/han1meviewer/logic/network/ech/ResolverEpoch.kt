package com.yenaly.han1meviewer.logic.network.ech

/** Access under the owning resolver's lock. A generation also protects A → B → A switches. */
internal class ResolverEpoch<T> {
    private var identity: T? = null
    private var generation = 0L

    fun observe(next: T): Long {
        if (identity != next) {
            identity = next
            generation++
        }
        return generation
    }

    fun invalidate() {
        identity = null
        generation++
    }

    fun isCurrent(expected: T, ticket: Long): Boolean = identity == expected && generation == ticket
}
