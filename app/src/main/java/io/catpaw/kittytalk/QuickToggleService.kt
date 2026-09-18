package io.catpaw.kittytalk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.math.min

/**
 * 系统快捷设置面板的功能开关磁贴。
 * 点击切换软件功能总开关（触发/不触发），磁贴实时显示开关状态。
 * 图标用猫爪表情"៸៸᳐>⩊<៸៸᳐"绘制。
 */
class QuickToggleService : TileService() {

    companion object {
        private const val PREFS_NAME = "qq_settings"
        private const val KEY_FUNC_ENABLED = "func_enabled"
        // 外部（长按面板开关）请求刷新磁贴状态
        const val ACTION_REFRESH = "io.catpaw.kittytalk.action.REFRESH_TILE"
        // 磁贴图标尺寸（px），系统磁贴图标区域约 24-32dp，这里用较小的尺寸避免放不下
        private const val ICON_SIZE_PX = 64
        private const val ICON_TEXT = "៸៸᳐>⩊<៸៸᳐"
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) updateTile()
        return START_NOT_STICKY
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_FUNC_ENABLED, true)
        prefs.edit().putBoolean(KEY_FUNC_ENABLED, !current).apply()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val on = prefs.getBoolean(KEY_FUNC_ENABLED, true)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (on) "言出化喵" else "已暂停"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "触发中" else "已关闭"
        }
        // 用猫爪表情绘制图标
        tile.icon = android.graphics.drawable.Icon.createWithBitmap(createIconBitmap(on))
        tile.updateTile()
    }

    /** 把猫爪表情文字绘制成 Bitmap 图标 */
    private fun createIconBitmap(on: Boolean): Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = size * 0.58f  // 文字大小，确保表情完整显示
            color = if (on) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        // 垂直居中
        val fontMetrics = paint.fontMetrics
        val baseline = (size - fontMetrics.top - fontMetrics.bottom) / 2f
        canvas.drawText(ICON_TEXT, size / 2f, baseline, paint)
        return bitmap
    }
}
