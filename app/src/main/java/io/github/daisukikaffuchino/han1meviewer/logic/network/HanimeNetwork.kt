package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.GETCHU_BASE_URL
import io.github.daisukikaffuchino.han1meviewer.HANIME_BASE_URL
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.GetchuService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeBaseService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeCommentService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeMyListService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeSubscriptionService

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:35
 */
object HanimeNetwork {
    var hanimeService = _hanimeService
        private set
    var getchuService = _getchuService
        private set
    var commentService = _commentService
        private set
    var myListService = _myListService
        private set
    var subscriptionService = _subscriptionService
        private set

    private val _hanimeService
        get() = ServiceCreator.create<HanimeBaseService>(HANIME_BASE_URL)

    private val _getchuService
        get() = ServiceCreator.createGetchu<GetchuService>(GETCHU_BASE_URL)

    private val _commentService
        get() = ServiceCreator.create<HanimeCommentService>(HANIME_BASE_URL)

    private val _myListService
        get() = ServiceCreator.create<HanimeMyListService>(HANIME_BASE_URL)

    private val _subscriptionService
        get() = ServiceCreator.create<HanimeSubscriptionService>(HANIME_BASE_URL)

    fun rebuildNetwork() {
        ServiceCreator.rebuildOkHttpClient()
        hanimeService = _hanimeService
        getchuService = _getchuService
        commentService = _commentService
        myListService = _myListService
    }
}
