package io.github.umbraproxy

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun comparesVersionsNumericallyAndIgnoresBuildMetadata() {
        assertEquals(true, isNewerVersion("v1.10.0", "1.9.9"))
        assertEquals(false, isNewerVersion("v1.5.6", "1.5.6+build.2"))
        assertEquals(false, isNewerVersion("v1.5.5", "1.5.6"))
        assertEquals(true, isNewerVersion("v2.0.0", "1.99.99"))
    }

    @Test fun stableReleaseSupersedesItsPrereleaseAndPrereleasesSortCorrectly() {
        assertEquals(true, isNewerVersion("v1.6.0", "1.6.0-rc.2"))
        assertEquals(false, isNewerVersion("v1.6.0-rc.10", "1.6.0"))
        assertEquals(true, isNewerVersion("1.6.0-rc.10", "1.6.0-rc.2"))
        assertEquals(true, isNewerVersion("1.6.0-alpha.1", "1.6.0-alpha"))
    }

    @Test fun rejectsMalformedVersions() {
        for (version in listOf("latest", "1.5", "1.5.6/evil", "1.05.6", "1.5.6-rc..1", "1.5.6-01", "99999999999999999999.0.0")) {
            assertNull(version, isNewerVersion(version, "1.5.6"))
            assertNull(version, isNewerVersion("1.5.6", version))
        }
    }

    @Test fun installedPackageLibrariesDetermineVariant() {
        assertEquals(ApkVariant.ARM64, ApkVariant.fromLibraries(setOf("arm64-v8a")))
        assertEquals(ApkVariant.ARMV7, ApkVariant.fromLibraries(setOf("armeabi-v7a")))
        assertEquals(ApkVariant.UNIVERSAL, ApkVariant.fromLibraries(setOf("arm64-v8a", "armeabi-v7a")))
        assertNull(ApkVariant.fromLibraries(emptySet()))
    }

    @Test fun selectsMatchingBuildAndSupportsExistingReleaseNames() {
        val assets = listOf(asset("UmbraProxy-v1.5.5-arm64-v8a.apk"), asset("UmbraProxy-v1.5.5-armeabi-v7a.apk"))
        assertEquals(assets[0], AppUpdates.selectAsset(assets, "v1.5.5", ApkVariant.ARM64))
        assertEquals(assets[1], AppUpdates.selectAsset(assets, "v1.5.5", ApkVariant.ARMV7))
        assertNull(AppUpdates.selectAsset(assets, "v1.5.5", ApkVariant.UNIVERSAL))
    }

    @Test fun fallsBackOnlyToUniversalAndRejectsForeignDownloadUrls() {
        val universal = asset("UmbraProxy-universal.apk")
        val wrong = asset("UmbraProxy-arm64-v8a.apk")
        assertEquals(universal, AppUpdates.selectAsset(listOf(wrong, universal), "v1.5.6", ApkVariant.ARMV7))
        assertNull(AppUpdates.selectAsset(listOf(wrong), "v1.5.6", ApkVariant.ARMV7))
        val malicious = ReleaseAsset("UmbraProxy-arm64-v8a.apk", "https://example.org/UmbraProxy-arm64-v8a.apk")
        assertNull(AppUpdates.selectAsset(listOf(malicious), "v1.5.6", ApkVariant.ARM64))
        val exact = asset("UmbraProxy-armeabi-v7a.apk")
        assertEquals(exact, AppUpdates.selectAsset(listOf(universal, exact), "v1.5.6", ApkVariant.ARMV7))
    }

    @Test fun latestLinksSelectTheInstalledVariant() {
        assertEquals("https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-arm64-v8a.apk", AppUpdates.latestDownload(ApkVariant.ARM64))
        assertTrue(AppUpdates.latestDownload(ApkVariant.ARMV7).endsWith("/UmbraProxy-armeabi-v7a.apk"))
    }

    private fun asset(name: String) = ReleaseAsset(name, "https://github.com/RHineIx/UmbraProxy/releases/download/v1.5.6/$name")
}
