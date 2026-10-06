package com.flx_apps.digitaldetox

import com.flx_apps.digitaldetox.util.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun matchesTheOfferedLanguageByLanguageCode() {
        assertEquals("zh-CN", AppLanguage.match("zh-Hans-CN"))
        assertEquals("zh-CN", AppLanguage.match("zh-CN"))
        assertEquals("de", AppLanguage.match("de-AT"))
        assertEquals("en", AppLanguage.match("en"))
    }

    @Test
    fun followsTheSystemForAnythingElse() {
        assertEquals("", AppLanguage.match(""))
        assertEquals("", AppLanguage.match("fr-FR"))
    }
}
