package io.catpaw.kittytalk

import android.content.Context

/**
 * 日志 Tab：日志显示、刷新、滚动跟随
 * 注意：logFollowBottom / logProgrammaticScroll 字段留在 LauncherActivity 中
 */

internal fun LauncherActivity.setupDebugLog() {
    binding.tabLogs.refreshLogButton.setOnClickListener { refreshDebugLog() }
    binding.tabLogs.clearLogButton.setOnClickListener {
        AutomationBridge.clearLogs()
        refreshDebugLog()
    }
    binding.tabLogs.logScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
        if (logProgrammaticScroll) return@setOnScrollChangeListener
        val child = binding.tabLogs.logScroll.getChildAt(0)
        if (child != null && child.height > 0) {
            logFollowBottom = scrollY + binding.tabLogs.logScroll.height >= child.height - 40
        }
    }
    binding.tabLogs.jumpTopButton.setOnClickListener {
        logFollowBottom = false
        logProgrammaticScroll = true
        binding.tabLogs.logScroll.scrollTo(0, 0)
        binding.tabLogs.logScroll.post { logProgrammaticScroll = false }
    }
    binding.tabLogs.jumpBottomButton.setOnClickListener {
        logFollowBottom = true
        scrollLogToBottom()
    }
    binding.tabLogs.copyLogButton.setOnClickListener {
        val text = binding.tabLogs.debugLogText.text?.toString() ?: ""
        if (text.isBlank() || text == "（暂无日志）") {
            toast("暂无可复制的日志")
            return@setOnClickListener
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("日志", text))
        toast("已复制全部日志")
    }
    AutomationBridge.logListener = { runOnUiThread { refreshDebugLog() } }
    refreshDebugLog()
}

internal fun LauncherActivity.refreshDebugLog() {
    val logs = AutomationBridge.getLogs()
    binding.tabLogs.debugLogText.text =
        if (logs.isEmpty()) "（暂无日志）" else logs.joinToString("\n")
    if (logFollowBottom) scrollLogToBottom()
}

/** 等 TextView 完成 layout 后，把日志 ScrollView 精确滚到底部 */
internal fun LauncherActivity.scrollLogToBottom() {
    val scroll = binding.tabLogs.logScroll
    logProgrammaticScroll = true
    val doScroll = {
        val child = scroll.getChildAt(0)
        if (child != null) {
            val target = (child.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, target)
        }
        scroll.post { logProgrammaticScroll = false }
    }
    val child = scroll.getChildAt(0)
    if (child != null && child.height > 0 && scroll.height > 0) {
        doScroll()
    } else {
        scroll.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (scroll.viewTreeObserver.isAlive) {
                        scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                    doScroll()
                }
            }
        )
    }
}
