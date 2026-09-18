package io.catpaw.kittytalk

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.catpaw.kittytalk.R

/** 明暗模式 */
enum class ThemeMode { FOLLOW, LIGHT, DARK }

/** 配色（跟随系统动态取色，或内置预设） */
enum class Palette(val resName: String) {
    SYSTEM("system"),
    PURPLE("purple"),
    BLUE("blue"),
    GREEN("green"),
    PINK("pink"),
    ORANGE("orange");

    companion object {
        fun from(s: String?): Palette =
            entries.firstOrNull { it.resName == s } ?: SYSTEM
    }
}

data class ThemeConfig(
    val themeMode: ThemeMode,
    val palette: Palette,
    val version: Int
)

/** 主题（明暗 + 配色）的存取与映射。 */
object PaletteStore {

    private const val PREFS = "qq_settings"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_COLOR = "color_mode"
    private const val KEY_VERSION = "theme_version"
    // 旧版暗色开关，用于迁移
    private const val KEY_LEGACY_DARK = "dark_mode"

    fun load(context: Context): ThemeConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = when (p.getString(KEY_MODE, null)) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> if (p.getBoolean(KEY_LEGACY_DARK, false)) ThemeMode.DARK else ThemeMode.FOLLOW
        }
        val palette = Palette.from(p.getString(KEY_COLOR, "system"))
        return ThemeConfig(mode, palette, p.getInt(KEY_VERSION, 0))
    }

    @Synchronized
    fun save(context: Context, mode: ThemeMode, palette: Palette) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putString(KEY_MODE, mode.name.lowercase())
            .putString(KEY_COLOR, palette.resName)
            .putInt(KEY_VERSION, p.getInt(KEY_VERSION, 0) + 1)
            // 清掉旧版开关，避免误判
            .remove(KEY_LEGACY_DARK)
            .apply()
    }

    fun appcompatNightMode(mode: ThemeMode): Int = when (mode) {
        ThemeMode.FOLLOW -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun themeStyleRes(palette: Palette): Int = when (palette) {
        Palette.SYSTEM -> R.style.Theme_QQReplyApp
        Palette.PURPLE -> R.style.Theme_QQReplyApp_Purple
        Palette.BLUE -> R.style.Theme_QQReplyApp_Blue
        Palette.GREEN -> R.style.Theme_QQReplyApp_Green
        Palette.PINK -> R.style.Theme_QQReplyApp_Pink
        Palette.ORANGE -> R.style.Theme_QQReplyApp_Orange
    }

    fun modeLabel(mode: ThemeMode): String = when (mode) {
        ThemeMode.FOLLOW -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "暗色"
    }

    fun paletteLabel(palette: Palette): String = when (palette) {
        Palette.SYSTEM -> "跟随系统"
        Palette.PURPLE -> "紫色"
        Palette.BLUE -> "蓝色"
        Palette.GREEN -> "绿色"
        Palette.PINK -> "粉色"
        Palette.ORANGE -> "橙色"
    }
}