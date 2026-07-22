package com.modrith.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildVariantTest {
    @Test
    fun versionNameIsConfigured() {
        assertFalse(BuildConfig.VERSION_NAME.isBlank())
        assertTrue(BuildConfig.VERSION_CODE > 0)
        assertTrue(BuildConfig.GIT_COMMIT.matches(Regex("[0-9a-f]{7}|unknown")))
        assertTrue(
            BuildConfig.BUILD_DATE_UTC.matches(
                Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC"),
            ),
        )
    }
}
