package com.yenaly.han1meviewer.logic.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAuthorizationHostTest {
    @Test
    fun authorizationIsScopedToHttpsGitHubApiUrls() {
        assertTrue(isGitHubApiUrl("api.github.com", isHttps = true))
        assertTrue(isGitHubApiUrl("API.GITHUB.COM", isHttps = true))
        assertFalse(isGitHubApiUrl("api.github.com", isHttps = false))
        assertFalse(isGitHubApiUrl("gh.anglesya.win", isHttps = true))
        assertFalse(isGitHubApiUrl("api.github.com.evil.example", isHttps = true))
        assertFalse(isGitHubApiUrl("github.com", isHttps = true))
    }
}
