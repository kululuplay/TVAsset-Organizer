/*
 * FakeSharedPreferences.kt
 * In-memory SharedPreferences for pure-JVM unit tests (no Robolectric). Only
 * the primitives our code touches are backed by the map; the rest follow the
 * same contract but are unused. commit() and apply() both take effect
 * immediately, which is exactly what the crash-guard tests need to observe.
 */
package com.iptv.player.util

import android.content.SharedPreferences

class FakeSharedPreferences : SharedPreferences {

    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals.add(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            applyPending()
            return true
        }

        override fun apply() = applyPending()

        private fun applyPending() {
            if (clearAll) values.clear()
            for (key in removals) values.remove(key)
            values.putAll(pending)
        }
    }
}
