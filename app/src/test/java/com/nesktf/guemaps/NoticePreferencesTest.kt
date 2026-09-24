package com.nesktf.guemaps

import android.content.SharedPreferences
import com.nesktf.guemaps.ui.notice.NoticePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class NoticePreferencesTest {

    @Test
    fun testNoticePreferencesFlagLifecycle() {
        val memoryMap = mutableMapOf<String, Any>()

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> {
                        memoryMap[args[0] as String] = args[1] as Boolean
                        proxy
                    }
                    "apply", "commit" -> true
                    else -> null
                }
            }
        ) as SharedPreferences.Editor

        val sharedPrefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getBoolean" -> {
                        val key = args[0] as String
                        val def = args[1] as Boolean
                        memoryMap[key] as? Boolean ?: def
                    }
                    "edit" -> editorProxy
                    else -> null
                }
            }
        ) as SharedPreferences

        // Verify keys
        assertEquals("guemaps_prefs", NoticePreferences.PREFS_NAME)
        assertEquals("disable_startup_notice", NoticePreferences.KEY_DISABLE_STARTUP_NOTICE)

        // 1. Initially notice is not disabled
        assertFalse(NoticePreferences.isNoticeDisabled(sharedPrefsProxy))

        // 2. Disable notice
        NoticePreferences.setNoticeDisabled(sharedPrefsProxy, true)
        assertTrue(NoticePreferences.isNoticeDisabled(sharedPrefsProxy))

        // 3. Re-enable notice
        NoticePreferences.setNoticeDisabled(sharedPrefsProxy, false)
        assertFalse(NoticePreferences.isNoticeDisabled(sharedPrefsProxy))
    }
}
