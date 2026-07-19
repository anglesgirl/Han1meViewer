package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.mylist

import android.app.Application
import io.github.daisukikaffuchino.han1meviewer.Preferences
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListItems
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListType
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FavSubViewModel(application: Application) : MyListSubViewModel(application) {

    var favVideoPage = 1
    private var csrfToken: String? = null

    val favVideoStateFlow: StateFlow<PageLoadingState<MyListItems<HanimeInfo>>> = itemsStateFlow.asStateFlow()
    val favVideoFlow: StateFlow<List<HanimeInfo>> = itemsFlow.asStateFlow()

    fun getMyFavVideoItems(userId: String, page: Int) {
        loadItems(MyListType.FAV_VIDEO, userId, page) { csrfToken = it.csrfToken }
    }

    private val _deleteMyFavVideoFlow = MutableSharedFlow<WebsiteState<Boolean>>()
    val deleteMyFavVideoFlow = _deleteMyFavVideoFlow.asSharedFlow()

    fun deleteMyFavVideo(videoCode: String, position: Int) {
        deleteItem(
            deleteCall = {
                NetworkRepo.addToMyFavVideo(
                    videoCode = videoCode,
                    likeStatus = true,
                    currentUserId = Preferences.savedUserId,
                    token = csrfToken,
                )
            },
            emitTo = _deleteMyFavVideoFlow,
            position = position,
            mapState = { it },
        )
    }

    override fun clearMyListItems() {
        super.clearMyListItems()
        favVideoPage = 1
    }
}
