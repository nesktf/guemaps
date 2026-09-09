package com.nesktf.guemaps

import com.nesktf.guemaps.data.remote.InMemoryCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarTest {

    @Test
    fun testInMemoryCookieJarSavesAndLoads() {
        val jar = InMemoryCookieJar()
        val url = "https://salta.miredbus.com.ar/captcha.png".toHttpUrl()

        val cookie = Cookie.Builder()
            .domain("salta.miredbus.com.ar")
            .name("JSESSIONID")
            .value("ABC12345XYZ")
            .path("/")
            .build()

        jar.saveFromResponse(url, listOf(cookie))

        val balanceUrl = "https://salta.miredbus.com.ar/rest/getSaldoCaptcha/123/456".toHttpUrl()
        val loaded = jar.loadForRequest(balanceUrl)

        assertEquals(1, loaded.size)
        assertEquals("JSESSIONID", loaded[0].name)
        assertEquals("ABC12345XYZ", loaded[0].value)
    }

    @Test
    fun testInMemoryCookieJarReplacesDuplicateCookie() {
        val jar = InMemoryCookieJar()
        val url = "https://salta.miredbus.com.ar/captcha.png".toHttpUrl()

        val cookie1 = Cookie.Builder()
            .domain("salta.miredbus.com.ar")
            .name("JSESSIONID")
            .value("FIRST")
            .build()

        val cookie2 = Cookie.Builder()
            .domain("salta.miredbus.com.ar")
            .name("JSESSIONID")
            .value("SECOND")
            .build()

        jar.saveFromResponse(url, listOf(cookie1))
        jar.saveFromResponse(url, listOf(cookie2))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("SECOND", loaded[0].value)
    }
}
