package io.catpaw.kittytalk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 下拉快捷磁贴“长按”弹出的开关面板。
 * 通过 QS_TILE_PREFERENCES intent-filter 注册，系统长按磁贴时打开本页（替代默认的应用详情页）。
 *
 * 本 Activity 配置为独立、透明、不进最近任务的浮窗，视觉上只弹出对话框，不会把应用主界面带到前台。
 * 提供：软件总开关、悬浮窗、标点触发、句号消费、分句触发五个快捷开关，改动即时生效。
 */
class TilePanelActivity : AppCompatActivity() {

    private data class Toggle(val label: String, val key: String, val def: Boolean)

    private val toggles = listOf(
        Toggle("软件开关", "func_enabled", true),
        Toggle("悬浮窗", "__overlay__", false),
        Toggle("标点触发", "punctuation_trigger", false),
        Toggle("句号消费", "punct_eat_period", false),
        Toggle("分句触发（仅粘贴时）", "sentence_trigger", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
        val labels = toggles.map { it.label }.toTypedArray()
        val checked = BooleanArray(toggles.size) { i ->
            when (toggles[i].key) {
                // 悬浮窗开关是即时显隐，不读 prefs：直接按当前悬浮窗是否存在勾选
                "__overlay__" -> OverlayInput.isOpen()
                else -> prefs.getBoolean(toggles[i].key, toggles[i].def)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("快捷开关")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                applyToggle(toggles[which].key, isChecked)
            }
            .setPositiveButton("关闭", null)
            .setOnDismissListener { finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** 写入开关并立即让处理逻辑生效（不依赖完整 reload 的时序，直接同步处理器标志位兜底） */
    private fun applyToggle(key: String, value: Boolean) {
        when (key) {
            "__overlay__" -> {
                // 悬浮窗即时显隐：勾选则弹出，取消则彻底关闭
                if (value) {
                    if (!android.provider.Settings.canDrawOverlays(this)) {
                        android.widget.Toast.makeText(this, "请先授权悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show()
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                    } else {
                        OverlayInput.show(this, manual = true)
                    }
                } else {
                    OverlayInput.dismiss()
                }
                return
            }
            else -> getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
        }

        val bridge = AutomationBridge.instance
        when (key) {
            "func_enabled" -> bridge?.setFuncEnabled(value)
            "punctuation_trigger" -> {
                if (bridge != null) bridge.reload() else PhraseProcessor.punctuationTrigger = value
            }
            "punct_eat_period" -> {
                if (bridge != null) bridge.reload() else PhraseProcessor.punctEatPeriod = value
            }
            "sentence_trigger" -> {
                if (bridge != null) bridge.reload() else PhraseProcessor.sentenceTrigger = value
            }
        }

        // 通知磁贴服务刷新图标 / 文案
        try {
            startService(
                Intent(this, QuickToggleService::class.java)
                    .setAction(QuickToggleService.ACTION_REFRESH)
            )
        } catch (_: Exception) {
        }
    }
}
