package com.modrith.app

import org.junit.Assert.assertFalse
import org.junit.Test

class BuildVariantTest {
    @Test
    fun versionNameIsConfigured() {
        assertFalse(BuildConfig.VERSION_NAME.isBlank())
    }
}
