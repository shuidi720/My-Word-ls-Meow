package io.catpaw.kittytalk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 悬浮窗输入：无障碍写入被拦截时的替代通道，也支持【非无障碍模式】手动打开独立使用。
 *  - 触发：无障碍写入失败或连续读不到输入框时自动弹出；也可在设置页手动打开（无需无障碍服务）
 *  - 行为：在悬浮窗输入框打字即【实时】应用与文本框一致的替换管线
 *    （PhraseProcessor.transformFloat，规则/后缀/触发开关每次处理前重新加载），替换结果由“复制变语”按钮复制后粘贴使用
 *  - UI：统一使用 applicationContext + 应用主题（DayNight）——无论从主界面还是无障碍服务打开，
 *    浅色/深色主题都正常跟随；全局单例，避免同时出现两个悬浮窗
 */
class OverlayInput(
    context: Context,
    private val onClosed: () -> Unit = {}
) {

    companion object {
        private const val PANEL_WIDTH = 320
        private const val PROCESS_DELAY_MS = 50L
        private const val TOAST_MIN_INTERVAL_MS = 2500L

        @Volatile
        private var instance: OverlayInput? = null

        /** 悬浮窗当前是否处于“收起悬浮窗”状态：收起时不自动弹出悬浮窗打扰用户 */
        fun isCollapsed(): Boolean = instance != null && instance?.expanded == false

        /** 悬浮窗当前是否存在（展开或收起悬浮窗都算打开） */
        fun isOpen(): Boolean = instance != null

        /** 全局唯一悬浮窗：任何入口（主界面手动 / 无障碍写入被拦截）都复用同一个实例。
         *  manual=true 时为用户手动开启，清除关闭后冷却时间，立即恢复弹窗能力 */
        fun show(context: Context, manual: Boolean = false) {
            val app = context.applicationContext
            if (manual) OverlayPrefs.clearCooldown(app)
            if (instance == null) {
                instance = OverlayInput(app) { instance = null }
            }
            instance?.show()
        }

        /** 彻底关闭当前悬浮窗 */
        fun dismiss() {
            try { instance?.destroy() } catch (_: Throwable) {}
            instance = null
        }
    }

    // 统一 applicationContext + 应用主题：浅色/深色跟随系统正常切换（修复服务入口创建时只有浅色的问题）
    private val context: Context =
        androidx.appcompat.view.ContextThemeWrapper(context.applicationContext, R.style.Theme_QQReplyApp)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var panel: View? = null
    private var ball: View? = null
    private var etInput: EditText? = null
    private var actionRow: LinearLayout? = null
    private var expanded = false

    // 悬浮窗自己的回显状态：上次写入的文本（用于防 TextWatcher 递归）
    private var lastSet = ""
    private var lastToastTime = 0L

    private val processRunnable = Runnable { processText() }

    /** 显示悬浮窗（已显示则忽略；无悬浮窗权限时提示） */
    fun show() {
        if (expanded && panel?.parent != null) return
        try {
            if (panel == null) buildPanel()
            showExpanded()
        } catch (e: Exception) {
            toast("悬浮窗权限未开启，请到应用设置中允许“显示在其他应用上层”")
        }
    }

    private fun buildPanel() {
        // —— 主题取色（跟随应用浅色/深色主题，浅色=白底深字，深色=黑底浅字） ——
        val panelBg = themeColor(android.R.attr.colorBackground, Color.rgb(255, 255, 255))
        val textPrimary = themeColor(android.R.attr.textColorPrimary, Color.rgb(33, 33, 33))
        val textSecondary = themeColor(android.R.attr.textColorSecondary, Color.rgb(117, 117, 117))
        val accent = themeColor(android.R.attr.colorAccent, Color.rgb(96, 90, 170))
        val strokeColor = (accent and 0x00FFFFFF) or 0x40000000 // 主题色描边（25% 透明度）
        val inputBg = blend(panelBg, accent, 0.06f)
        val inputStroke = blend(accent, panelBg, 0.35f)
        val buttonText = Color.WHITE

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), strokeColor)
            }
            setPadding(dp(16), 0, dp(16), dp(16))
        }

        // 标题行（可拖动）
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        val title = TextView(context).apply {
            text = "🐾 变语悬浮窗"
            textSize = 16f
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val collapseBtn = Button(context).apply {
            text = "—"
            textSize = 16f
            setTextColor(textSecondary)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { collapseToBall() }
        }
        titleRow.addView(collapseBtn)
        titleRow.setOnClickListener { autoFocusInput() }
        titleRow.setOnTouchListener(FloatingMoveListener())
        root.addView(titleRow)

        // 提示
        root.addView(TextView(context).apply {
            text = "无障碍写入被拦截时自动弹出。在这里打字会实时变换语句，点“复制变语”去聊天框粘贴发送；点“—”收起悬浮窗，可拖动换位置。"
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        // 输入框（实时触发；不设置 hint，避免 hint 被无障碍读成文本导致误替换）
        val input = EditText(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            minLines = 3
            maxLines = 8
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), inputStroke)
            }
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onInputChanged(s?.toString() ?: "")
                }
            })
        }
        etInput = input
        root.addView(input)

        // 操作行
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        actionRow = row
        val copyBtn = Button(context).apply {
            text = "复制变语"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(buttonText)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { copyCurrent() }
        }
        row.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        val closeBtn = Button(context).apply {
            text = "关闭"
            textSize = 14f
            setTextColor(textSecondary)
            background = GradientDrawable().apply {
                setColor(blend(panelBg, accent, 0.10f))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), inputStroke)
            }
            setOnClickListener { showCooldownDialog() }
        }
        row.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        root.addView(row)

        panel = ScrollView(context).apply {
            addView(root)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    collapseToBall()
                    true
                } else {
                    false
                }
            }
        }
    }

    /** 读取主题属性颜色；失败时回退到默认色 */
    private fun themeColor(attr: Int, fallback: Int): Int {
        return try {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, fallback)
            ta.recycle()
            c
        } catch (_: Exception) {
            fallback
        }
    }

    /** 按比例混合两色（t=0 全部 base，t=1 全部 overlay）；ARGB 各通道独立插值 */
    private fun blend(base: Int, overlay: Int, t: Float): Int {
        val a = (base ushr 24) + (((overlay ushr 24) - (base ushr 24)) * t).toInt()
        val r = ((base ushr 16) and 0xFF) + ((((overlay ushr 16) and 0xFF) - ((base ushr 16) and 0xFF)) * t).toInt()
        val g = ((base ushr 8) and 0xFF) + ((((overlay ushr 8) and 0xFF) - ((base ushr 8) and 0xFF)) * t).toInt()
        val b = (base and 0xFF) + (((overlay and 0xFF) - (base and 0xFF)) * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun buildBall() {
        // 收起态悬浮球：优先用应用图标，取不到时退化为主题色圆底
        val icon = ImageView(context)
        val appIcon = runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
        if (appIcon != null) {
            icon.setImageDrawable(appIcon)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = dp(4)
            icon.setPadding(inset, inset, inset, inset)
        } else {
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(android.R.attr.colorAccent, Color.rgb(96, 90, 170)))
            }
        }
        icon.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        icon.contentDescription = "展开变语悬浮窗"
        icon.setOnClickListener { showExpanded() }
        icon.setOnTouchListener(FloatingMoveListener())
        ball = icon
    }

    /** 输入实时处理：延迟后应用与文本框一致的增量变换，变化则写回（不自动复制，由"复制当前喵语"按钮复制） */
    private fun onInputChanged(text: String) {
        if (text == lastSet) return // 防回显（写回触发）
        handler.removeCallbacks(processRunnable)
        handler.postDelayed(processRunnable, PROCESS_DELAY_MS)
    }

    private fun processText() {
        val input = etInput ?: return
        val raw = input.text?.toString() ?: return
        if (raw.isEmpty()) {
            lastSet = ""
            return
        }
        val isDeleting = lastSet.isNotEmpty() && raw.length < lastSet.length
        // 非无障碍模式也走同一套引擎：处理前重新加载最新配置（规则/后缀/触发开关/屏蔽符号）
        PhraseProcessor.reload(context)
        val (target, cursor) = PhraseProcessor.transformFloat(raw, lastSet, isDeleting)
        if (target == raw) {
            lastSet = raw
            return
        }
        lastSet = target
        input.setText(target)
        // 光标定位：纯句尾附加模式光标停在附加文字之前（打字顺滑不重复触发）；标点输入光标在末尾
        input.setSelection(cursor.coerceIn(0, target.length))
    }

    private fun copyCurrent() {
        val input = etInput ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("输入框还是空的，先写点想说的话")
            return
        }
        if (copyToClipboard(text)) {
            // 清空输入框并重置回显状态：下次展开可直接输入，无需手动清字
            handler.removeCallbacks(processRunnable)
            input.setText("")
            lastSet = ""
            toast("已复制变语，去聊天框长按粘贴发送")
            // 复制后自动收起开关：开启→收起成小球；关闭→保持展开（直接读设置，非无障碍模式同样生效）
            val collapseOnCopy = try {
                context.getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
                    .getBoolean("float_collapse_on_copy", true)
            } catch (_: Exception) {
                true
            }
            if (collapseOnCopy) {
                collapseToBall()
            }
        } else {
            toast("复制失败，请检查剪贴板权限")
        }
    }

    private fun copyToClipboard(text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nhy", text))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun toast(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < TOAST_MIN_INTERVAL_MS) return
        lastToastTime = now
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun showExpanded() {
        removeViews()
        val lp = WindowManager.LayoutParams(
            dp(PANEL_WIDTH),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16)
        lp.y = dp(160)
        try {
            wm.addView(panel, lp)
            expanded = true
            panel?.postDelayed({ autoFocusInput() }, 200L)
        } catch (_: Exception) {
            toast("悬浮窗权限未开启，请到应用设置中允许“显示在其他应用上层”")
        }
    }

    private fun collapseToBall() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            panel?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        } catch (_: Exception) {
        }
        removeViews()
        if (ball == null) buildBall()
        val lp = WindowManager.LayoutParams(
            dp(40), dp(40),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16)
        lp.y = dp(160)
        try {
            wm.addView(ball, lp)
            expanded = false
        } catch (_: Exception) {
        }
    }

    private fun autoFocusInput() {
        try {
            val p = panel
            if (p != null && p.parent != null) {
                val lp = p.layoutParams as WindowManager.LayoutParams
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                wm.updateViewLayout(p, lp)
                etInput?.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                etInput?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            }
        } catch (_: Exception) {
        }
    }

    private fun removeViews() {
        try {
            panel?.let { if (it.parent != null) wm.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            ball?.let { if (it.parent != null) wm.removeView(it) }
        } catch (_: Exception) {
        }
    }

    /** 关闭后冷却时间选择：在悬浮窗内部显示选项行（AlertDialog 无法用 applicationContext 弹出） */
    private fun showCooldownDialog() {
        val row = actionRow ?: run { destroy(); return }
        // 保存原始按钮，取消时恢复
        val originalViews = (0 until row.childCount).map { row.getChildAt(it) }.toList()
        row.removeAllViews()
        val textPrimary = themeColor(android.R.attr.textColorPrimary, Color.rgb(33, 33, 33))
        val accent = themeColor(android.R.attr.colorAccent, Color.rgb(96, 90, 170))
        val panelBg = themeColor(android.R.attr.colorBackground, Color.rgb(255, 255, 255))
        val options = listOf("10分钟" to 10, "30分钟" to 30, "1小时" to 60, "直接关" to 0)
        options.forEachIndexed { idx, (label, minutes) ->
            val btn = Button(context).apply {
                text = label
                textSize = 12f
                setTextColor(if (idx == 3) textPrimary else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (idx == 3) blend(panelBg, accent, 0.10f) else accent)
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener {
                    if (minutes > 0) {
                        OverlayPrefs.setCooldownMinutes(context, minutes)
                        toast("已设置冷却，$label 内不自动弹出悬浮窗")
                    } else {
                        OverlayPrefs.clearCooldown(context)
                    }
                    destroy()
                }
            }
            row.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(4)
            })
        }
        // 取消按钮：恢复原始操作行
        val cancelBtn = Button(context).apply {
            text = "取消"
            textSize = 12f
            setTextColor(textPrimary)
            background = GradientDrawable().apply {
                setColor(blend(panelBg, accent, 0.06f))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                row.removeAllViews()
                originalViews.forEach { row.addView(it) }
            }
        }
        row.addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
        })
    }

    /** 彻底关闭悬浮窗（下次被拦截时重新弹出） */
    fun destroy() {
        handler.removeCallbacks(processRunnable)
        removeViews()
        panel = null
        ball = null
        etInput = null
        lastSet = ""
        onClosed()
    }

    private fun dp(v: Int): Int = Math.round(v * context.resources.displayMetrics.density)

    /**
     * 悬浮窗拖动处理：按住移动超过系统 touchSlop 判定为拖动并实时更新窗口坐标；
     * 未超过 slop 抬手则视为点击，转交 View 的 OnClickListener（小球展开 / 标题栏聚焦）。
     */
    private inner class FloatingMoveListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private val slop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val window = if (expanded) panel else ball
            if (window == null || window.parent == null) return false
            val lp = window.layoutParams as? WindowManager.LayoutParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (e.rawX - downX).toInt()
                    val deltaY = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(deltaX) > slop || kotlin.math.abs(deltaY) > slop)) {
                        moved = true
                    }
                    if (moved) {
                        lp.x = initialX + deltaX
                        lp.y = initialY + deltaY
                        runCatching { wm.updateViewLayout(window, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> moved = false
                else -> return false
            }
            return true
        }
    }
}
