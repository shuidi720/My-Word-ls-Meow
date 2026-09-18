package io.catpaw.kittytalk

import android.content.Context

/**
 * 悬浮窗偏好管理：不弹窗列表、关闭后冷却时间、写入成功计数。
 * 存储在 qq_settings SharedPreferences 中，与其他开关配置一致。
 */
object OverlayPrefs {
    private const val PREFS = "qq_settings"
    private const val KEY_NO_POPUP_PACKAGES = "overlay_no_popup_packages"
    private const val KEY_COOLDOWN_UNTIL = "overlay_cooldown_until"
    private const val KEY_WRITE_SUCCESS_COUNT = "overlay_write_success_count"

    /** 不弹窗列表：在这些应用里无障碍写入正常，不自动弹出悬浮窗 */
    fun getNoPopupPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NO_POPUP_PACKAGES, emptySet()) ?: emptySet()
    }

    fun addNoPopupPackage(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = getNoPopupPackages(context).toMutableSet()
        if (set.add(pkg)) {
            prefs.edit().putStringSet(KEY_NO_POPUP_PACKAGES, set).apply()
        }
    }

    fun removeNoPopupPackage(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = getNoPopupPackages(context).toMutableSet()
        if (set.remove(pkg)) {
            prefs.edit().putStringSet(KEY_NO_POPUP_PACKAGES, set).apply()
        }
    }

    fun isNoPopupPackage(context: Context, pkg: String): Boolean {
        return pkg.isNotBlank() && getNoPopupPackages(context).contains(pkg)
    }

    /** 关闭后冷却截止时间戳（毫秒）；在此时间之前不自动弹出悬浮窗 */
    fun getCooldownUntil(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_COOLDOWN_UNTIL, 0L)
    }

    fun setCooldownMinutes(context: Context, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60 * 1000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_COOLDOWN_UNTIL, until).apply()
    }

    fun clearCooldown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_COOLDOWN_UNTIL, 0L).apply()
    }

    fun isInCooldown(context: Context): Boolean {
        return System.currentTimeMillis() < getCooldownUntil(context)
    }

    /** 每个包名的连续写入成功计数：达到阈值自动加入不弹窗列表 */
    private fun getWriteSuccessCount(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WRITE_SUCCESS_COUNT, null) ?: return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) parts[1].toIntOrNull()?.let { parts[0] to it } else null
        }.toMap()
    }

    private fun saveWriteSuccessCount(context: Context, counts: Map<String, Int>) {
        val raw = counts.entries.joinToString(",") { "${it.key}=${it.value}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WRITE_SUCCESS_COUNT, raw).apply()
    }

    /** 记录一次写入成功，返回是否因此自动加入了不弹窗列表 */
    fun recordWriteSuccess(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val counts = getWriteSuccessCount(context).toMutableMap()
        val newCount = (counts[pkg] ?: 0) + 1
        counts[pkg] = newCount
        // 只保留最近 20 个应用的计数，避免无限增长
        val trimmed = counts.entries.sortedByDescending { it.value }.take(20).associate { it.toPair() }
        saveWriteSuccessCount(context, trimmed)
        // 连续 1 次写入成功自动加入不弹窗列表
        if (newCount >= 1 && !isNoPopupPackage(context, pkg)) {
            addNoPopupPackage(context, pkg)
            return true
        }
        return false
    }

    /** 写入失败时重置该应用的计数 */
    fun resetWriteSuccessCount(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val counts = getWriteSuccessCount(context).toMutableMap()
        counts.remove(pkg)
        saveWriteSuccessCount(context, counts)
    }
}
