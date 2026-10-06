package com.flx_apps.digitaldetox.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The in-app language picker. Android 13+ keeps the choice in the system's per-app language
 * setting ([LocaleManager]), so it also shows up in the system settings and reaches every service
 * and widget. Older versions have no such setting: the choice is kept in a preference and applied
 * by [wrap] where a context is created (activities, application, overlay service).
 *
 * ponytail: below Android 13 the accessibility service (status notification, lock toasts) and the
 * quick settings tile keep following the system language. Wrap their context too if that ever
 * matters.
 */
object AppLanguage {
    /** Language tag to native name; add new res/values-* languages here too. */
    val options = listOf("en" to "English", "de" to "Deutsch", "zh-CN" to "简体中文")

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** The [options] tag matching [tag] by language ("zh-Hans-CN" matches "zh-CN"), else "". */
    fun match(tag: String): String =
        options.firstOrNull { it.first.substringBefore('-') == tag.substringBefore('-') }?.first
            ?: ""

    /** The chosen language's tag, or "" while following the system. */
    fun current(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            savedTag(context)
        }
        return match(tag)
    }

    /** Switches the language to [tag] ("" follows the system) and refreshes the open screen. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // the system recreates the activities itself
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TAG, tag).apply()
            activity.recreate()
        }
    }

    /**
     * Applies the saved language to [base] below Android 13, for `attachBaseContext`. Only the
     * locale is overridden, so rotation, dark mode and font scale keep following the system.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = savedTag(base)
        if (tag.isEmpty()) {
            Locale.setDefault(base.resources.configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale) // date and number formatting asks for the default locale
        return base.createConfigurationContext(Configuration().apply { setLocale(locale) })
    }

    private fun savedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "") ?: ""
}
