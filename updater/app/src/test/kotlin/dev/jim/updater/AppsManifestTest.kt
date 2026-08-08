package dev.jim.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsManifestTest {
    private fun release(
        tag: String,
        vararg assets: String,
    ) = ReleaseInfo(
        tagName = tag,
        assets = assets.map { ReleaseAsset(it, "https://example.test/$it") },
        body = "",
    )

    @Test
    fun parseReleaseTag_rejectsMalformedAndOverflowingTags() {
        assertEquals(ParsedReleaseTag("chat", "1.2.3", 42), parseReleaseTag("chat-v1.2.3+42"))
        assertNull(parseReleaseTag("chat-v1.2+42"))
        assertNull(parseReleaseTag("chat-v1.2.3"))
        assertNull(parseReleaseTag("chat-v1.2.3+not-a-code"))
        assertNull(parseReleaseTag("chat-v1.2.3+999999999999999999999999"))
    }

    @Test
    fun discoverApps_ignoresMalformedAndUnknownReleases_andChoosesHighestCode() {
        val manifest = mapOf(
            "chat" to ManifestEntry("dev.example.chat", "Chat", emptySet()),
        )
        val releases = listOf(
            release("chat-v1.2.3+5", "chat.apk"),
            release("chat-v1.2.3+12", "chat.apk"),
            release("chat-v1.2+999", "chat.apk"),
            release("unknown-v9.9.9+999", "unknown.apk"),
            release("chat-v1.2.3+999999999999999999999999", "chat.apk"),
        )

        val apps = discoverApps(manifest, releases)

        assertEquals(1, apps.size)
        assertEquals("1.2.3", apps.single().remoteVersion)
        assertEquals(12L, apps.single().baseRemoteCode)
    }

    @Test
    fun discoverApps_allSeesUntaggedAndCohortBuildSeesOnlyMatchingApps() {
        val manifest = mapOf(
            "public" to ManifestEntry("dev.example.public", "Public", emptySet()),
            "beta" to ManifestEntry("dev.example.beta", "Beta", setOf("beta")),
            "other" to ManifestEntry("dev.example.other", "Other", setOf("other")),
        )
        val releases = listOf(
            release("public-v1.0.0+1", "public.apk"),
            release("beta-v1.0.0+1", "beta.apk"),
            release("other-v1.0.0+1", "other.apk"),
        )

        val allNames = discoverApps(manifest, releases, COHORT_ALL).map { it.entry.displayName }.toSet()
        assertEquals(setOf("Beta", "Other", "Public"), allNames)
        assertEquals(listOf("Beta"), discoverApps(manifest, releases, "beta")
            .map { it.entry.displayName })
    }

    @Test
    fun downloadAsset_returnsNullWhenExpectedAssetIsMissing() {
        val manifest = mapOf(
            "chat" to ManifestEntry("dev.example.chat", "Chat", emptySet()),
        )
        val app = discoverApps(
            manifest,
            listOf(release("chat-v1.0.0+1", "chat-arm64-v8a.apk")),
        ).single()

        assertEquals("chat-arm64-v8a.apk", downloadAsset(app, "arm64-v8a")?.name)
        assertNull(downloadAsset(app, "armeabi-v7a"))
        assertNull(downloadAsset(app.copy(entry = app.entry.copy(hasAbiSplit = false))))
    }

    @Test
    fun visibilityRules_keepAllAsSuperuserAndRequireExplicitTailoredMembership() {
        assertTrue(isVisibleTo(COHORT_ALL, emptySet()))
        assertTrue(isVisibleTo("beta", setOf("beta", "other")))
        assertFalse(isVisibleTo("beta", emptySet()))
        assertFalse(isVisibleTo("beta", setOf("other")))
    }
}
