package com.yenaly.han1meviewer.logic.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAuthFallbackTest {

    @Test
    fun tokenIsAddedOnlyToHttpsGitHubApiRequests() {
        val apiRequest = request("https://api.github.com/repos/anglesgirl/Han1meViewer").withGitHubApiToken("test-token")
        assertEquals("Bearer test-token", apiRequest.header("Authorization"))

        val mirrorRequest = request("https://gh.anglesya.win/anglesgirl/Han1meViewer/releases/download/v1.4.0/app.apk")
            .withGitHubApiToken("test-token")
        assertNull(mirrorRequest.header("Authorization"))

        val githubDownload = request("https://github.com/anglesgirl/Han1meViewer/releases/download/v1.4.0/app.apk")
            .withGitHubApiToken("test-token")
        assertNull(githubDownload.header("Authorization"))

        val insecureApiRequest = request("http://api.github.com/repos/anglesgirl/Han1meViewer")
            .withGitHubApiToken("test-token")
        assertNull(insecureApiRequest.header("Authorization"))
    }

    @Test
    fun unauthorizedGitHubApiRequestRetriesWithoutAuthorization() {
        val retry = retryGitHubApiAnonymously(
            response("https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest", "Bearer test-token"),
        )

        assertNotNull(retry)
        assertEquals("https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest", retry!!.url.toString())
        assertNull(retry.header("Authorization"))
    }

    @Test
    fun doesNotRetryAnAlreadyAnonymousRequest() {
        assertNull(
            retryGitHubApiAnonymously(
                response("https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest", null),
            ),
        )
    }

    @Test
    fun doesNotRetryMirrorOrOtherHostRequests() {
        assertNull(
            retryGitHubApiAnonymously(
                response("https://gh.anglesya.win/anglesgirl/Han1meViewer/releases/download/v1.4.0/app.apk", "Bearer test-token"),
            ),
        )
        assertNull(
            retryGitHubApiAnonymously(
                response("https://github.com/anglesgirl/Han1meViewer/releases/latest", "Bearer test-token"),
            ),
        )
    }

    @Test
    fun doesNotRetryNon401ResponsesOrNonBearerAuthorization() {
        assertNull(
            retryGitHubApiAnonymously(
                response("https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest", "Bearer test-token", 403),
            ),
        )
        assertNull(
            retryGitHubApiAnonymously(
                response("https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest", "Basic dummy"),
            ),
        )
    }

    private fun request(url: String): Request = Request.Builder().url(url).build()

    private fun response(url: String, authorization: String?, code: Int = 401): Response {
        val request = Request.Builder().url(url).apply {
            if (authorization != null) header("Authorization", authorization)
        }.build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 401) "Unauthorized" else "Forbidden")
            .build()
    }
}
