package io.github.daisukikaffuchino.han1meviewer.logic.state

sealed class PageState<out T> {
    data class Success<out T>(
        val info: T,
        val isNextPageLoading: Boolean = false,
        val isRefreshing: Boolean = false
    ) : PageState<T>()

    data object Loading : PageState<Nothing>()

    data class NoMoreData<out T>(val info: T) : PageState<T>()

    data object Empty : PageState<Nothing>()

    data class Error<out T>(
        val throwable: Throwable,
        val cachedInfo: T? = null
    ) : PageState<T>()
}
val <T> PageState<T>.hasData: Boolean
    get() = when (this) {
        is PageState.Success -> true
        is PageState.NoMoreData -> true
        is PageState.Error -> cachedInfo != null
        else -> false
    }

val <T> PageState<T>.dataOrNull: T?
    get() = when (this) {
        is PageState.Success -> info
        is PageState.NoMoreData -> info
        is PageState.Error -> cachedInfo
        else -> null
    }