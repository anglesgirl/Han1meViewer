package com.yenaly.han1meviewer.logic

import android.util.Base64
import android.util.Log
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.EMPTY_STRING
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.Preferences.isAlreadyLogin
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.exception.CloudFlareBlockedException
import com.yenaly.han1meviewer.logic.exception.HanimeNotFoundException
import com.yenaly.han1meviewer.logic.exception.IPBlockedException
import com.yenaly.han1meviewer.logic.exception.ParseException
import com.yenaly.han1meviewer.logic.model.CommentPlace
import com.yenaly.han1meviewer.logic.model.CreatorSort
import com.yenaly.han1meviewer.logic.model.ModifiedPlaylistArgs
import com.yenaly.han1meviewer.logic.model.MyListType
import com.yenaly.han1meviewer.logic.model.OnlineWatchHistorySort
import com.yenaly.han1meviewer.logic.model.VideoCommentArgs
import com.yenaly.han1meviewer.logic.model.VideoComments
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.HUpdater
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import com.yenaly.han1meviewer.logic.network.HanimeNetwork
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import com.yenaly.han1meviewer.logic.state.VideoLoadingState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.yenaly_libs.utils.applicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import javax.net.ssl.SSLHandshakeException

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:38
 */
object NetworkRepo {

    //<editor-fold desc="Hanime">

    fun getHomePage() = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getHomePage(Preferences.homeUrl) },
        action = Parser::homePageVer2
    )

    fun getHanimeSearchResult(
        page: Int, query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<String>, brands: Set<String>,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.hanimeService.getHanimeSearchResult(
                page, query, genre, sort,
                if (broad) "on" else null,
                date, duration, tags, brands
            )
        },
        action = Parser::hanimeSearch
    )

    fun getHanimeVideo(videoCode: String) = videoIOFlow(
        request = { HanimeNetwork.hanimeService.getHanimeVideo(videoCode) },
        action = Parser::hanimeVideoVer2
    )

    fun getHanimePreview(date: String) = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getHanimePreview(date) },
        action = Parser::hanimePreview
    )

    //获取订阅或者可以说是关注列表及它们的更新
    fun getMySubscriptions(page: Int) = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getMySubscriptions(page) },
        action = Parser::getMySubscriptions
    )
    //</editor-fold>

    //<editor-fold desc="My List">

    fun getMyListItems(userId: String, listType: Any, page: Int) = pageIOFlow(
        request = {
            when (listType) {
                is String ->
                    HanimeNetwork.myListService.getMyListItems(userId, listType, page)

                is MyListType ->
                    HanimeNetwork.myListService.getMyListItems(userId, listType.value, page)

                else ->
                    throw IllegalArgumentException("typeOrId must be String or MyListType")
            }
        },
        action = Parser::myListItems
    )

    fun getMyPlayListItems(page: Int = 1, listCode: String = "0") = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getMyPlayListItems(listCode, page)
        },
        action = Parser::myPlayListItems
    )

    fun getOnlineWatchHistories(
        userId: String,
        sort: OnlineWatchHistorySort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getOnlineWatchHistories(userId, sort.value, page)
        },
        action = Parser::onlineWatchHistoryItems,
    )

    fun getUserAccountPage(userId: String) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getUserAccountPage(userId) },
        action = Parser::userAccountPage,
    )

    fun getUploadedVideos(
        userId: String,
        sort: CreatorSort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getUploadedVideos(userId, sort.value, page)
        },
        action = Parser::creatorUploadedItems,
    )

    fun getUploadingVideos(
        userId: String,
        sort: CreatorSort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getUploadingVideos(userId, sort.value, page)
        },
        action = Parser::creatorUploadingItems,
    )

    fun updateUserAccountProfile(
        userId: String,
        csrfToken: String?,
        name: String,
        email: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountProfile(
                userId = userId,
                csrfToken = csrfToken,
                name = name,
                email = email,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountPassword(
        userId: String,
        csrfToken: String?,
        oldPassword: String,
        newPassword: String,
        newPasswordConfirm: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountPassword(
                userId = userId,
                csrfToken = csrfToken,
                oldPassword = oldPassword,
                newPassword = newPassword,
                newPasswordConfirm = newPasswordConfirm,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountAvatar(
        userId: String,
        csrfToken: String?,
        avatarFile: File,
    ) = websiteIOFlow(
        request = {
            val imageRequestBody = avatarFile.asRequestBody("image/jpeg".toMediaType())
            val imagePart = MultipartBody.Part.createFormData(
                "photo",
                avatarFile.name,
                imageRequestBody,
            )
            HanimeNetwork.myListService.updateUserAccountAvatar(
                userId = userId,
                csrfToken = (csrfToken ?: EMPTY_STRING).toRequestBody("text/plain".toMediaType()),
                method = "patch".toRequestBody("text/plain".toMediaType()),
                type = "photo".toRequestBody("text/plain".toMediaType()),
                photo = imagePart,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun deleteOnlineWatchHistory(
        videoCode: String,
        position: Int,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.deleteOnlineWatchHistory(
                videoCode = videoCode,
                csrfToken = csrfToken,
            )
        },
    ) {
        val jsonObject = JSONObject(it)
        val success = jsonObject.optBoolean("success", false)
        if (success) {
            WebsiteState.Success(position)
        } else {
            WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
        }
    }

    fun deleteMyListItems(
        itemId: String,
        position: Int,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.deleteMyListItems(
                itemId = itemId,
                csrfToken = token
            )
        },
    ) { deleteBody ->
        val jsonObject = JSONObject(deleteBody)
        val success = jsonObject.optBoolean("success", false)
        if (success) {
            WebsiteState.Success(position)
        } else {
            WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
        }
    }

    fun getPlaylists(page: Int, userId: String ) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getPlaylists(userId, page) },
        action = Parser::playlists
    )

    fun addToMyFavVideo(
        videoCode: String,
        likeStatus: Boolean, // false => "": add fav; true => "1": cancel fav;
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyFavVideo(
                videoCode, if (likeStatus) "1" else EMPTY_STRING,
                token, currentUserId
            )
        }
    ) {
        Log.d("add_to_fav_body", it)
        return@websiteIOFlow WebsiteState.Success(likeStatus)
    }

    fun rateVideo(
        videoCode: String,
        isPositive: Boolean,
        likeStatus: Boolean,
        unlikeStatus: Boolean,
        likesCount: Int,
        unlikesCount: Int,
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.rateVideo(
                videoCode = videoCode,
                isPositive = if (isPositive) 1 else 0,
                likeStatus = if (likeStatus) "1" else EMPTY_STRING,
                unlikeStatus = if (unlikeStatus) "1" else EMPTY_STRING,
                likesCount = likesCount,
                unlikesCount = unlikesCount,
                csrfToken = token,
                userId = currentUserId,
            )
        }
    ) {
        Log.d("rate_video_body", it)
        return@websiteIOFlow WebsiteState.Success(isPositive)
    }

    fun createPlaylist(
        videoCode: String,
        title: String,
        description: String,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.createPlaylist(
                csrfToken, videoCode, title, description
            )
        },
        permittedSuccessCode = intArrayOf(500)
    ) {
        Log.d("create_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun addToMyList(
        listCode: String,
        videoCode: String,
        isChecked: Boolean,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyList(
                csrfToken, listCode, videoCode, isChecked
            )
        }
    ) {
        Log.d("add_to_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun modifyPlaylist(
        listCode: String,
        title: String,
        description: String,
        delete: Boolean,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.modifyPlaylist(
                listCode, title, description,
                if (delete) "on" else null,
                csrfToken
            )
        },
        permittedSuccessCode = intArrayOf(302)
    ) {
        Log.d("modify_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(
            ModifiedPlaylistArgs(
                title = title, desc = description, isDeleted = delete,
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="Comment">

    fun getComments(type: String, code: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getComments(type, code) },
        action = Parser::comments
    )

    fun getCommentReply(commentId: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getCommentReply(commentId) },
        action = Parser::commentReply
    )

    fun postComment(
        csrfToken: String?,
        currentUserId: String,
        targetUserId: String,
        type: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postComment(
                csrfToken, currentUserId,
                type, targetUserId, text
            )
        }
    ) {
        Log.d("post_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun postCommentReply(
        csrfToken: String?,
        replyCommentId: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postCommentReply(
                csrfToken, replyCommentId, text
            )
        }
    ) {
        Log.d("post_comment_reply_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun likeComment(
        csrfToken: String?,
        commentPlace: CommentPlace,
        foreignId: String?,
        isPositive: Boolean, // 你選擇的是讚還是踩，1是讚，0是踩
        likeUserId: String?,
        commentLikesCount: Int,
        commentLikesSum: Int,
        likeCommentStatus: Boolean, // 你之前有沒有點過讚，1是0否
        unlikeCommentStatus: Boolean, // 你之前有沒有點過踩，1是0否
        commentPosition: Int, comment: VideoComments.VideoComment,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.likeComment(
                csrfToken, commentPlace.value, foreignId,
                if (isPositive) 1 else 0,
                likeUserId, commentLikesCount, commentLikesSum,
                if (likeCommentStatus) 1 else 0,
                if (unlikeCommentStatus) 1 else 0
            )
        }
    ) {
        Log.d("like_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(
            VideoCommentArgs(
                commentPosition, isPositive, comment
            )
        )
    }

    fun reportComment(
        csrfToken: String?,
        reason: String,
        currentUserId: String?,
        redirectUrl: String,
        reportableType: String?,
        reportableId: String?
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.submitReport(
                userId = currentUserId,
                csrfToken = csrfToken,
                redirectUrl = redirectUrl,
                reportableId = reportableId,
                reportableType = reportableType,
                reason = reason
            )
        },
        action = Parser::reportCommentResponse
    )

    //</editor-fold>

    //<editor-fold desc="Subscription">

    fun subscribeArtist(
        csrfToken: String?,
        userId: String,
        artistId: String,
        // 这里表示目标状态
        status: Boolean,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.subscriptionService.subscribeArtist(
                csrfToken, userId, artistId,
                if (status) "" else "1"
            )
        }
    ) {
        Log.d("subscribe_artist_body", it)
        return@websiteIOFlow WebsiteState.Success(status)
    }

    //</editor-fold>

    //<editor-fold desc="Base">

    fun getLatestVersion(forceCheck: Boolean = true) = flow {
        emit(WebsiteState.Loading)
        val versionInfo = HUpdater.checkForUpdate(forceCheck)
        emit(WebsiteState.Success(versionInfo))
    }.catch { e ->
        when (e) {
            is CancellationException -> throw e
            else -> {
                e.printStackTrace()
                emit(WebsiteState.Error(e))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun login(email: String, password: String) = flow<WebsiteState<String>> {
        emit(WebsiteState.Loading)
        val baseUrl = com.yenaly.han1meviewer.HANIME_BASE_URL
        val loginUrl = "${baseUrl}login"
        val dohUrl = com.yenaly.han1meviewer.logic.network.DohConfig.resolveUrl()
            ?: "https://82sew1c85i.cloudflare-gateway.com/dns-query"
        val dohHost = android.net.Uri.parse(dohUrl).host
            ?: "82sew1c85i.cloudflare-gateway.com"
        val dohResolve = "$dohHost:443:${DohConfig.bootstrapIps().ifEmpty {
            listOf("162.159.36.20", "162.159.36.5")
        }.joinToString(",")}"

        fun parseResponse(raw: String): JSONObject = JSONObject(raw)
        fun cookiesFrom(response: JSONObject): LinkedHashMap<String, String> {
            val result = linkedMapOf<String, String>()
            val headers = response.optJSONArray("headers") ?: return result
            for (i in 0 until headers.length()) {
                val line = headers.optString(i)
                if (!line.contains('\t')) continue
                if (!line.substringBefore('\t').equals("set-cookie", true)) continue
                val pair = line.substringAfter('\t').substringBefore(';').trim()
                val name = pair.substringBefore('=', "").trim()
                if (name.isNotEmpty() && pair.contains('=')) {
                    // 宽松解析：直接取 name=value，不做 Cookie.parse 校验，避免因域名/路径不匹配丢掉 remember_web
                    result[name] = pair.substringAfter('=')
                }
            }
            return result
        }
        fun cookieHeader(cookies: Map<String, String>) =
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun cookieMap(raw: String): LinkedHashMap<String, String> {
            val result = linkedMapOf<String, String>()
            raw.split(';').forEach { item ->
                val name = item.substringBefore('=', "").trim()
                if (name.isNotEmpty() && item.contains('=')) result[name] = item.substringAfter('=')
            }
            return result
        }

        if (!EchHttpClient.isLoaded) EchHttpClient.init(applicationContext)
        val getResponse = parseResponse(EchHttpClient.request(
            "GET", loginUrl, arrayOf("User-Agent: $USER_AGENT"), null, dohUrl, dohResolve
        ))
        val cookies = cookiesFrom(getResponse)
        cookies.putAll(cookieMap(Preferences.cloudFlareCookie.cookie))
        Diagnostics.event("jni_login_get", mapOf(
            "host" to (android.net.Uri.parse(loginUrl).host ?: "unknown"),
            "status" to getResponse.optInt("statusCode"),
            "ech_status" to getResponse.optString("echStatus"),
            "cookie_names" to cookies.keys.joinToString(","),
            "has_cf_clearance" to cookies.containsKey("cf_clearance"),
        ))
        val html = getResponse.optString("body").let { encoded ->
            if (encoded.isBlank()) "" else String(Base64.decode(encoded, Base64.DEFAULT))
        }
        val token = Parser.extractTokenFromLoginPage(html)
        Diagnostics.event("jni_login_token", mapOf(
            "host" to (android.net.Uri.parse(loginUrl).host ?: "unknown"),
            "token_len" to token.length,
            "body_len" to html.length,
        ))
        if (cookies.isEmpty()) throw IllegalStateException("登录会话 Cookie 为空")

        val xsrfDecoded = try {
            java.net.URLDecoder.decode(cookies["XSRF-TOKEN"].orEmpty(), "UTF-8")
        } catch (_: Exception) { cookies["XSRF-TOKEN"].orEmpty() }
        val form = "_token=${java.net.URLEncoder.encode(token, "UTF-8")}" +
            "&email=${java.net.URLEncoder.encode(email, "UTF-8")}" +
            "&password=${java.net.URLEncoder.encode(password, "UTF-8")}" +
            "&remember=1"
        Diagnostics.event("jni_login_post_start", mapOf(
            "host" to (android.net.Uri.parse(loginUrl).host ?: "unknown"),
            "body_len" to form.toByteArray(Charsets.UTF_8).size,
            "cookie_names" to cookies.keys.joinToString(","),
        ))
        val postHeaders = mutableListOf(
            "User-Agent: $USER_AGENT",
            "Content-Type: application/x-www-form-urlencoded",
            "Cookie: ${cookieHeader(cookies)}",
            "Referer: $loginUrl",
            "Origin: ${baseUrl.removeSuffix("/")}",
        )
        if (xsrfDecoded.isNotBlank()) {
            postHeaders += "X-XSRF-TOKEN: $xsrfDecoded"
            postHeaders += "X-CSRF-TOKEN: $token"
        }
        val postResponse = parseResponse(EchHttpClient.request(
            "POST", loginUrl,
            postHeaders.toTypedArray(),
            form.toByteArray(Charsets.UTF_8), dohUrl, dohResolve
        ))
        cookies.putAll(cookiesFrom(postResponse))
        // 后备：用新 token 直连 302 补 remember_web（验证器已跑通链路）
        val needFallback = cookies.keys.none { it.contains("remember_web", true) } && postResponse.optInt("statusCode") in 200..399
        Diagnostics.event("jni_login_fallback_enter", mapOf(
            "need_fallback" to needFallback,
            "has_remember" to cookies.keys.any { it.contains("remember_web", true) },
            "post_status" to postResponse.optInt("statusCode"),
            "host" to android.net.Uri.parse(loginUrl).host,
        ))
        if (needFallback) {
            try {
                // 1) 重新 ECH GET 拿新 token/XSRF（避免旧 token 已消耗导致 419）
                val freshGet = parseResponse(EchHttpClient.request(
                    "GET", loginUrl,
                    arrayOf("User-Agent: $USER_AGENT", "Cookie: ${cookieHeader(cookies)}"),
                    null, dohUrl, dohResolve
                ))
                cookies.putAll(cookiesFrom(freshGet))
                // 显式再合并一次 cloudFlareCookie（含 cf_clearance），防被覆盖
                cookies.putAll(cookieMap(Preferences.cloudFlareCookie.cookie))
                val freshHtml = freshGet.optString("body").let { e ->
                    if (e.isBlank()) "" else String(Base64.decode(e, Base64.DEFAULT))
                }
                val freshToken = Parser.extractTokenFromLoginPage(freshHtml)
                if (freshToken.isNotBlank()) {
                    val freshXsrf = try {
                        java.net.URLDecoder.decode(cookies["XSRF-TOKEN"].orEmpty(), "UTF-8")
                    } catch (_: Exception) { cookies["XSRF-TOKEN"].orEmpty() }
                    val freshForm = "_token=${java.net.URLEncoder.encode(freshToken, "UTF-8")}" +
                        "&email=${java.net.URLEncoder.encode(email, "UTF-8")}" +
                        "&password=${java.net.URLEncoder.encode(password, "UTF-8")}" +
                        "&remember=1"
                    // 2) 直连 POST，不跟跳转，拿 302 的 Set-Cookie（含 remember_web）
                    // 完全复制验证器的头部，确保 cf_clearance 等随行
                    val fallbackCookies = LinkedHashMap<String, String>().apply {
                        putAll(cookies)
                        putAll(cookieMap(Preferences.cloudFlareCookie.cookie)) // 强制带上 cf_clearance
                    }
                    Diagnostics.event("jni_login_fallback_pre", mapOf(
                        "cookie_names" to fallbackCookies.keys.joinToString(","),
                        "has_cf" to fallbackCookies.containsKey("cf_clearance"),
                        "has_remember" to fallbackCookies.keys.any { it.contains("remember_web", true) },
                    ))
                    val conn = (java.net.URL(loginUrl).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                        connectTimeout = 15000; readTimeout = 15000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("Accept", "text/html,application/xhtml+xml")
                        setRequestProperty("Origin", "https://${android.net.Uri.parse(loginUrl).host}")
                        setRequestProperty("Referer", loginUrl)
                        setRequestProperty("Cookie", cookieHeader(fallbackCookies))
                        if (freshXsrf.isNotBlank()) {
                            setRequestProperty("X-XSRF-TOKEN", freshXsrf)
                            setRequestProperty("X-CSRF-TOKEN", freshToken)
                        }
                    }
                    conn.outputStream.use { it.write(freshForm.toByteArray(Charsets.UTF_8)) }
                    val dCode = conn.responseCode
                    val dLoc = conn.getHeaderField("Location").orEmpty()
                    conn.headerFields.entries
                        .filter { it.key?.equals("set-cookie", true) == true }
                        .flatMap { it.value }
                        .forEach { raw ->
                            val pair = raw.substringBefore(';').trim()
                            val n = pair.substringBefore('=', "").trim()
                            if (n.isNotEmpty() && pair.contains('=')) cookies[n] = pair.substringAfter('=')
                        }
                    Diagnostics.event("jni_login_remember_fallback", mapOf(
                        "code" to dCode, "loc" to dLoc,
                        "has_remember" to cookies.keys.any { it.contains("remember_web", true) },
                        "cookie_names" to cookies.keys.joinToString(","),
                    ))
                } else {
                    Diagnostics.event("jni_login_remember_fallback", mapOf(
                        "code" to -1, "loc" to "",
                        "has_remember" to false,
                        "error" to "fresh_token_empty",
                    ))
                }
            } catch (e: Exception) {
                Diagnostics.event("jni_login_remember_fallback_error", mapOf(
                    "error" to e.message ?: e.javaClass.simpleName,
                    "host" to android.net.Uri.parse(loginUrl).host,
                ))
            }
        }
        val postFinalUrl = postResponse.optString("url", loginUrl)
        val postStatus = postResponse.optInt("statusCode")
        Diagnostics.event("jni_login_post_result", mapOf(
            "host" to (android.net.Uri.parse(loginUrl).host ?: "unknown"),
            "status" to postStatus,
            "final_path" to (android.net.Uri.parse(postFinalUrl).path ?: "/"),
            "ech_status" to postResponse.optString("echStatus"),
            "cookie_names" to cookies.keys.joinToString(","),
        ))
        val postBody = postResponse.optString("body").let { encoded ->
            if (encoded.isBlank()) "" else String(Base64.decode(encoded, Base64.DEFAULT))
        }
        // POST 返回 200 不能代表登录成功；用 POST 后最新 Cookie 复核主页。
        val verifyResponse = parseResponse(EchHttpClient.request(
            "GET", baseUrl,
            arrayOf(
                "User-Agent: $USER_AGENT",
                "Cookie: ${cookieHeader(cookies)}",
                "Referer: $loginUrl",
            ),
            null, dohUrl, dohResolve
        ))
        val verifyBody = verifyResponse.optString("body").let { encoded ->
            if (encoded.isBlank()) "" else String(Base64.decode(encoded, Base64.DEFAULT))
        }
        val finalPath = android.net.Uri.parse(postFinalUrl).path.orEmpty()
        val redirectedBackToLogin = finalPath == "/login" || finalPath.startsWith("/login?")
        val verifyLower = verifyBody.lowercase()
        val hasUserMarker = verifyLower.contains("logout") || verifyLower.contains("user-modal-name") ||
            verifyLower.contains("user-modal-trigger") || verifyLower.contains("登出")
        val success = postStatus in 200..399 && !redirectedBackToLogin
        Diagnostics.event("jni_login_verify_result", mapOf(
            "host" to (android.net.Uri.parse(baseUrl).host ?: "unknown"),
            "status" to verifyResponse.optInt("statusCode"),
            "body_len" to verifyBody.length,
            "post_final_path" to finalPath,
            "redirected_back_to_login" to redirectedBackToLogin,
            "has_user_marker" to hasUserMarker,
        ))
        if (!success) {
            Log.w("NetworkRepo", "JNI 登录失败 code=${postResponse.optInt("statusCode")} bodyLen=${postBody.length}")
            throw IllegalStateException(getString(R.string.account_or_password_wrong))
        }
        Log.i("NetworkRepo", "JNI 登录成功 code=${postResponse.optInt("statusCode")} cookieNames=${cookies.keys}")
        emit(WebsiteState.Success(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }))
    }.catch { e ->
        Diagnostics.event("jni_login_exception", mapOf(
            "error_type" to e.javaClass.simpleName,
            "error" to (e.message ?: "unknown"),
        ))
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于单网页的情况
     *
     * @param permittedSuccessCode 用于处理特殊情况，比如[NetworkRepo.modifyPlaylist]需要302成功
     */
    private fun <T> websiteIOFlow(
        request: suspend () -> Response<ResponseBody>,
        permittedSuccessCode: IntArray? = null,
        action: (String) -> WebsiteState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        val permitted = permittedSuccessCode?.contains(requestResult.code()) == true
        if ((permitted || requestResult.isSuccessful)) {
            emit(action.invoke(resultBody ?: EMPTY_STRING))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于有page分页的情况
     */
    private fun <T> pageIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> PageLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于影片界面
     */
    private fun <T> videoIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> VideoLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(VideoLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    internal fun Response<ResponseBody>.throwRequestException(): Nothing {
        val body = errorBody()?.string()
        when (val code = code()) {
            403 -> if (!body.isNullOrBlank()) {
                when {
                    "you have been blocked" in body ->
                        throw IPBlockedException(getString(R.string.cloudflare_ip_block_warning))

                    "Just a moment" in body ->
                        throw CloudFlareBlockedException(getString(R.string.cloudflare_network_mismatch))

                    else ->
                        throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數不大時會報403
                }
            } else throw IllegalStateException("$code ${message()}")

            500 -> throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數很大時會報500

            404 -> if (!isAlreadyLogin) {
                throw IllegalStateException(getString(R.string.not_logged_in_currently))
            } else {
                throw IllegalStateException("$code ${message()}")
            }

            else -> throw IllegalStateException("$code ${message()}")
        }
    }

    internal fun handleException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException -> throw e
            is ParseException -> {
                e.printStackTrace()
                ParseException(getString(R.string.parse_error_msg))
            }

            is SSLHandshakeException -> {
                e.printStackTrace()
                SSLHandshakeException(getString(R.string.ssl_handshake_error))
            }

            else -> {
                e.printStackTrace()
                e
            }
        }
    }

    //</editor-fold>

    private fun getString(resId: Int) = applicationContext.getString(resId)
}
