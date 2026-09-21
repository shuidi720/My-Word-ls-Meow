package io.catpaw.kittytalk

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutomationBridge : AccessibilityService() {

    companion object {
        var instance: AutomationBridge? = null
        const val TAG = "QQReply"

        // ===== [加固] QQ 包名与输入框资源 ID 前缀加密（防 jadx/strings 直接看出针对对象）=====
        private const val PKG_MOBILEQQ = "com.tencent.mobileqq"
        private const val PKG_QQ = "com.tencent.qq"
        private const val PKG_TIM = "com.tencent.tim"
        private const val QQ_ID_PREFIX = "com.tencent.mobileqq:id/"
        private const val QQ2_ID_PREFIX = "com.tencent.qq:id/"
        // ==================================================================================

        // 内存调试日志缓冲：连续相同的日志自动合并，避免“功能开关已关闭”这类刷屏把缓冲撑爆
        private const val LOG_MAX = 400
        private data class LogEntry(
            val message: String,
            var firstTs: String,
            var lastTs: String,
            var count: Int = 1
        )
        private val logList = ArrayDeque<LogEntry>()
        /** 界面可注册它，在收到新日志时刷新显示（会在主线程回调） */
        @Volatile
        var logListener: (() -> Unit)? = null

        @Synchronized
        fun addLog(line: String) {
            val ts = android.text.format.DateFormat.format(
                "HH:mm:ss", java.util.Date()
            ).toString()
            val last = logList.lastOrNull()
            if (last != null && last.message == line) {
                // 与上一条相同：合并，更新末次时间与计数，不新增行
                last.lastTs = ts
                last.count += 1
            } else {
                logList.addLast(LogEntry(line, ts, ts))
                while (logList.size > LOG_MAX) logList.removeFirst()
            }
            instance?.onNewLog()
        }

        @Synchronized
        fun getLogs(): List<String> = logList.map { e ->
            when {
                e.count <= 1 -> "[${e.firstTs}] ${e.message}"
                e.firstTs == e.lastTs -> "[${e.firstTs}] ${e.message} *${e.count}"
                else -> "[${e.firstTs}/${e.lastTs}] ${e.message} *${e.count}"
            }
        }

        @Synchronized
        fun clearLogs() {
            logList.clear()
            instance?.onNewLog()
        }
    }

    /** 有服务实例时直接主线程回调，无实例则忽略（界面下拉刷新兜底） */
    private fun onNewLog() {
        val l = logListener
        if (l != null) mainHandler.post { l() }
    }

    // 当前是否为 QQ 系列（用专属 viewId 优先定位，否则走通用可编辑框查找）
    private var isQQ = false
    // 白名单应用条目标（包名/启用/显示名），仅启用且命中包名才处理该应用事件
    private var whitelist: MutableList<AppEntry> = mutableListOf()
    // 一键生效：开启后无需白名单，所有应用默认生效（系统界面无输入框会自动跳过）
    private var allAppsEnabled = false
    // 悬浮窗"复制后自动收起成小球"开关（默认开启；关闭则复制后保持展开）
    var floatCollapseOnCopy = true

    // 当前活动分类下的生效规则与后缀（多条，随机选一条已启用的）
    private var activeRules: List<Rule> = emptyList()
    private var activeSuffixes: List<Suffix> = emptyList()
    // 软件功能总开关：关闭时不做任何替换
    private var funcEnabled = true
    // 标点触发：开启后只在输入以句尾标点结尾（或点发送）时才执行替换，避免输入法拼字被打断
    private var punctuationTrigger = false
    // 标点触发-句号消费开关：开启后句尾句号"。"仅作为触发标识、触发后从文本中消失，不再保留在句末
    private var punctEatPeriod = false
    // 分句触发文字：开启后（仅粘贴时生效）按标点把文本分成多个子句，
    // 每个子句末尾都加附加文字（如："你好，世界。" -> "你好喵～，世界喵～。"）；
    // 开启时句末附加不再重复加，避免重复后缀
    private var sentenceTrigger = false
    // 分句/标点触发逻辑与屏蔽符号已统一收口到 PhraseProcessor（3.0）
    // Shizuku 系统保活：开启后服务启动时自动应用系统白名单并拉起常驻前台服务
    private var shizukuKeepAlive = false
    // 后台任务线程池（Shizuku 命令、保活操作）
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // 延迟触发：文本变化后等待用户停顿再处理，避免连续输入/删字时反复触发（v3.8.1 由 100ms 前移到 50ms 加快触发）
    private val TEXT_CHANGE_DELAY_MS = 50L
    private var pendingTextRunnable: Runnable? = null
    // 内容变化兜底：部分应用（YouTube 等原生/自绘控件）不发送文本变化事件，
    // 借窗口内容变化节流轮询处理（用户停顿后处理一次）（v3.8.1 由 100ms 前移到 50ms）
    private val CONTENT_CHANGE_DELAY_MS = 50L
    private var pendingContentRunnable: Runnable? = null
    // 写入验证：setText 返回成功但实际不生效（拦截写入）的应用，延迟重读校验，不一致则启用悬浮窗
    private var verifyPending = false
    private var lastSet = ""
    private var userOriginal = ""
    private var processing = false
    // 看门狗：processing 卡死（无障碍调用异常阻塞）超过 5 秒强制重置，避免服务假死（参考 QQZayuHelper）
    private var processingStart = 0L
    private val watchdogTask = object : Runnable {
        override fun run() {
            if (processing) {
                val elapsed = System.currentTimeMillis() - processingStart
                if (elapsed >= 5000L) {
                    addLog("看门狗：处理卡死 ${elapsed}ms，强制重置")
                    processing = false
                } else {
                    mainHandler.postDelayed(this, 5000L - elapsed)
                }
            }
        }
    }
    // 粘贴识别标记：最近的文本变化事件是否"粘贴式"（一次性插入大段文本）。
    // 标点触发+分句同开时，识别到粘贴会临时按分句处理（相当于暂时关闭标点触发），随后自动恢复
    private var lastPasteLike = false
    // 标点触发模式：最近一次"子串独立刷新"写入后，整句尚未附加句尾文字；
    // 打标点时若本次只输入了标点，则对整句补一次附加（如："你要打我"→子串刷新"你要打我去"，打","→"你要打我去喵，"）
    private var tailPending = false
    // 本次 doProcess 是否命中"子串独立刷新"（标点触发模式下，无标点但命中启用的子串规则）
    private var substringHitForRun = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = java.util.Random()
    // 事件日志节流：文本变化事件太多，只定期汇总打印
    private var textChangeCount = 0
    private var lastEventPkg = ""
    // 窗口切换标志：切换窗口后首次 doProcess 只同步 lastSet 不处理，
    // 避免切换到已有文本的输入框时自动把用户事先打的字全部变语（Bug6）
    private var justSwitchedWindow = false
    // 功能开关状态缓存：只在从开到关的瞬间打一次日志，避免关闭后每次事件都刷屏"功能开关已关闭"
    private var lastFuncOnState = true

    /** 窗口切换后延迟读取输入框，同步已有文本状态，避免用户第一次打字被误判为已有文本跳过 */
    private val syncInputStateTask = Runnable {
        try {
            val root = rootInActiveWindow ?: return@Runnable
            val inputNode = findInputField(root) ?: return@Runnable
            val raw = inputNode.text?.toString() ?: ""
            // 占位提示文本不同步：输入框未输入时显示的 hint 文案不是用户真实输入，
            // 同步后会导致用户首次打字时因 raw.length < lastSet.length 被误判为删除，句尾附加不触发
            val hint = if (Build.VERSION.SDK_INT >= 26) {
                runCatching { inputNode.hintText?.toString() }.getOrNull()
            } else null
            val isHint = raw.isNotEmpty() && !hint.isNullOrEmpty() && raw.trim() == hint.trim()
            // 同步已有文本状态：切窗口后输入框可能已有用户输入的文本，同步到 lastSet 避免被分句触发
            if (raw.isNotEmpty() && !isHint && inputNode.isFocused && inputNode.isEditable) {
                lastSet = raw
                userOriginal = raw
                addLog("窗口切换后同步：已有文本，仅同步状态不处理")
            }
        } catch (_: Exception) {
        } finally {
            justSwitchedWindow = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            instance = this
            addLog("服务 onCreate")
            reload()
            Log.d(TAG, "AutomationBridge onCreate")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate 异常: " + e.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        addLog("服务 onDestroy 实例已释放")
        // 注意：不主动停止 LifelineService——常驻前台服务由用户开关控制，
        // 与无障碍服务解耦，避免服务销毁时误杀保活
        instance = null
        Log.d(TAG, "AutomationBridge onDestroy")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        addLog("服务 onUnbind 被系统断开绑定")
        val r = super.onUnbind(intent)
        Log.d(TAG, "onUnbind")
        return r
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 绑定成功时初始化若出错也不能崩溃，否则开关会被系统回退。
        // 注意：这里不做任何前台服务启动——在部分国产系统（如 OriginOS/Android 15）上，
        // 无障碍绑定瞬间从后台启动前台服务会被系统拒绝，可能引发"服务无法运行"回弹。
        // 常驻前台服务改由用户在应用内主动开启（前台启动无限制），这里只做 Shizuku 白名单。
        try {
            reload()
            addLog("服务已连接并启动，功能开关=$funcEnabled")
            Log.d(TAG, "AutomationBridge onServiceConnected")
            showToast("言出化喵服务已启动")
            // Shizuku 系统保活：后台应用系统白名单，防止进程被杀、无障碍掉线
            if (shizukuKeepAlive) {
                bgExecutor.execute {
                    try {
                        val result = PrivilegeBridge.applyKeepAlive(this@AutomationBridge)
                        addLog("Shizuku 白名单结果：$result")
                    } catch (e: Throwable) {
                        addLog("Shizuku 白名单执行异常：${e.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onServiceConnected 异常: " + e.message)
        }
    }

    /** 从 SettingsRepository 重新加载配置：仅启用当前活动分类中的规则与后缀（3.0 起统一收口到 PhraseProcessor） */
    fun reload() {
        PhraseProcessor.reload(this)
        val cfg = SettingsRepository.load(this)
        val active = cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()
        activeRules = active?.rules ?: emptyList()
        activeSuffixes = (active?.suffixes ?: emptyList()).filter { it.enabled && it.text.isNotBlank() }
        // 统一取一次 prefs，避免 reload 内反复创建 SharedPreferences 实例（原连续读 8 次 → 1 次）
        val prefs = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
        funcEnabled = prefs.getBoolean("func_enabled", true)
        punctuationTrigger = prefs.getBoolean("punctuation_trigger", false)
        punctEatPeriod = prefs.getBoolean("punct_eat_period", false)
        sentenceTrigger = prefs.getBoolean("sentence_trigger", false)
        shizukuKeepAlive = prefs.getBoolean("shizuku_keepalive", false)
        allAppsEnabled = prefs.getBoolean("all_apps_enabled", false)
        floatCollapseOnCopy = prefs.getBoolean("float_collapse_on_copy", true)
        whitelist = WhitelistStore.load(this)
        Log.d(TAG, "规则分类=[${active?.name}] 规则数=[${activeRules.size}] 生效后缀数=[${activeSuffixes.size}] 功能=[$funcEnabled] 标点触发=[$punctuationTrigger] 句号消失=[$punctEatPeriod] 分句触发=[$sentenceTrigger] 一键生效=[$allAppsEnabled] Shizuku保活=[$shizukuKeepAlive] 白名单额外应用=[${whitelist.size}个]")
    }

    /** 由界面设置软件功能总开关 */
    fun setFuncEnabled(enabled: Boolean) {
        funcEnabled = enabled
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件处理全程兜底：任何异常都不能向上抛，否则系统会判定服务崩溃并自动关闭开关
        try {
            handleAccessibilityEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "onAccessibilityEvent 异常: " + e.message)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        lastEventPkg = pkg
        // 跳过本应用自身（悬浮窗输入框等）：悬浮窗由 OverlayInput 自己的 TextWatcher 实时处理，
        // 避免无障碍服务把悬浮窗输入当成外部输入框双重处理（修复：悬浮窗内文本被反复替换累积）
        if (pkg == packageName) return
        // 跳过输入法窗口：小米/红米（HyperOS）第三方输入法（如小米搜狗 com.sohu.inputmethod.sogou.xiaomi）
        // 在打字全程会反复以输入法包名上报“窗口切换”，一键生效放行所有包名后，这些事件会误触发
        // cancelPending()/justSwitchedWindow，把宿主输入框 50ms 后的替换任务反复取消，表现为“打字不触发”。
        // 宿主输入框文本事件的包名是宿主 App（B站/QQ 等），不受此过滤影响。
        if (isInputMethodPackage(pkg)) return
        // 仅处理已支持的应用：QQ 系列默认始终支持，其它应用需手动加入白名单
        if (!isSupportedPackage(pkg)) return
        isQQ = pkg.startsWith(PKG_MOBILEQQ) || pkg == PKG_QQ || pkg == PKG_TIM

        when (event.eventType) {
            // 切换窗口：重置防回显状态，并取消未执行的延迟任务
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                addLog("收到窗口切换事件 pkg=$pkg")
                textChangeCount = 0
                processing = false
                cancelPending()
                justSwitchedWindow = true
                // 延迟读取输入框同步已有文本状态：窗口切换时输入框可能还没获得焦点，
                // 等 150ms 后读取；读到已有长文本则同步 lastSet 不处理，读不到或短文本则只清标志，
                // 用户后续打字正常触发——避免第一次打字被误判为"已有文本"跳过。
                mainHandler.removeCallbacks(syncInputStateTask)
                mainHandler.postDelayed(syncInputStateTask, 150)
            }

            // 点击：若点击的是发送按钮，兜底处理一次（即使实时监听没触发也能替换）
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                addLog("收到点击事件 pkg=$pkg isSendButton=${isSendButton(event)}")
                if (isSendButton(event)) {
                    cancelPending()
                    doProcess(false)
                }
            }

            // 输入框文本变化：延迟触发，等待用户停顿后再处理（避免删字/连续输入时反复触发）
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 有新的文本变化事件说明输入框响应正常（回显/用户输入），取消写入验证；
                // 写入被吞的应用不会产生回显事件，验证会继续执行并在失败时启用悬浮窗
                verifyPending = false
                // 粘贴识别
                lastPasteLike = isPasteLikeEvent(event)
                scheduleTextChange()
                // 节流打印：每 50 次文本变化汇总一次
                textChangeCount++
                if (textChangeCount % 50 == 1) {
                    addLog("收到文本变化事件(累计 $textChangeCount 次) pkg=$pkg")
                }
            }

            // 窗口内容变化：部分应用不发送文本变化事件（YouTube 等自绘/原生控件），
            // 内容变化后节流轮询一次兜底处理（用户停顿后处理，避免高频重复）
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pendingTextRunnable == null && pendingContentRunnable == null) {
                    val r = Runnable {
                        pendingContentRunnable = null
                        doProcess(false, quietNoInput = true)
                    }
                    pendingContentRunnable = r
                    mainHandler.postDelayed(r, CONTENT_CHANGE_DELAY_MS)
                }
            }
        }
    }

    /** 延迟触发：取消上一次未执行的延迟任务，重新计时（用户停顿 TEXT_CHANGE_DELAY_MS 后才处理） */
    private fun scheduleTextChange() {
        cancelPending()
        val r = Runnable {
            pendingTextRunnable = null
            doProcess(true)
        }
        pendingTextRunnable = r
        mainHandler.postDelayed(r, TEXT_CHANGE_DELAY_MS)
    }

    /** 判断文本变化事件是否为"粘贴式"（基于本次新增的文本片段 slice）：
     *  - 纯文字（无标点/数字/特殊符号，可含空格）≥30 字 → 粘贴（避免拼音整句联想误判）
     *  - 文字+标点/换行 → 粘贴（短文本粘贴也分句）
     *  - 纯数字 / 纯标点 → 不识别（可能是输入法标点）
     *  - 含数字/特殊符号但无标点 → 不识别
     *  - 事件信息不全时：替换了选中内容（removed+added）也算粘贴 */
    private fun isPasteLikeEvent(event: AccessibilityEvent): Boolean {
        return try {
            val added = event.addedCount
            val removed = event.removedCount
            val from = event.fromIndex
            val textNow = event.text?.map { it?.toString() ?: "" }?.joinToString("") ?: ""
            val slice = if (added > 0 && from >= 0) {
                val s = from.coerceIn(0, textNow.length)
                val e = (from + added).coerceIn(0, textNow.length)
                if (e > s) textNow.substring(s, e) else ""
            } else ""
            if (slice.isEmpty()) return removed > 0 && added > 0
            // 标点集动态化：分句屏蔽框里的符号不算粘贴触发标点（字+屏蔽符号不因该符号识别为粘贴）
            val punctSet = PhraseProcessor.pastePunctChars()
            // 纯数字 / 纯标点（含换行）：不算粘贴
            val allDigit = slice.all { it.isDigit() }
            val allPunct = slice.all { it == '\n' || it in punctSet }
            if (allDigit || allPunct) return false
            val hasPunct = slice.any { it == '\n' || it in punctSet }
            val hasDigit = slice.any { it.isDigit() }
            // 字+标点/换行：含文字即识别为粘贴
            if (hasPunct) return slice.any { it.isLetter() }
            // 无标点：含数字或特殊符号（表情/颜文字等）不算粘贴
            if (hasDigit) return false
            val special = slice.any {
                !it.isLetter() && !it.isDigit() && it != '\n' && it !in punctSet && !it.isWhitespace()
            }
            if (special) return false
            // 纯文字（可含空格）：≥30 字才识别为粘贴
            slice.length >= 30
        } catch (_: Exception) {
            false
        }
    }

    /** 取消未执行的延迟替换任务 */
    private fun cancelPending() {
        val r = pendingTextRunnable
        if (r != null) {
            mainHandler.removeCallbacks(r)
            pendingTextRunnable = null
        }
        val cr = pendingContentRunnable
        if (cr != null) {
            mainHandler.removeCallbacks(cr)
            pendingContentRunnable = null
        }
    }
      /** 是否命中受支持的应用：QQ 系列默认始终生效，其它应用需在白名单中且处于启用状态 */
    private fun isSupportedPackage(pkg: String): Boolean =
        allAppsEnabled || pkg.startsWith(PKG_MOBILEQQ) || pkg == PKG_QQ ||
            pkg == PKG_TIM || whitelist.any { it.pkg == pkg && it.enabled }

    /** 是否为输入法窗口包名（输入法窗口永不处理，也不能让它的窗口事件打断宿主输入框的替换任务） */
    private fun isInputMethodPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return p.contains("inputmethod") ||
            p.startsWith("com.baidu.input") ||   // 百度输入法（含 vivo/小米定制 com.baidu.input_vivo/_mi）
            p.startsWith("com.sohu.inputmethod") // 搜狗输入法（含小米定制 com.sohu.inputmethod.sogou.xiaomi）
    }

    /**
     * 获取用于查找输入框的窗口根节点（返回的节点需由调用方 recycle）。
     * 首选当前激活窗口；当其是输入法窗口/本应用/为空时（小米/红米 HyperOS 打字时输入法会抢占 active window），
     * 遍历交互式窗口（需 flagRetrieveInteractiveWindows），按聚焦>活跃优先级挑一个宿主窗口。
     */
    private fun acquireRootForInput(): AccessibilityNodeInfo? {
        runCatching {
            val r = rootInActiveWindow
            if (r != null) {
                val p = r.packageName?.toString() ?: ""
                if (p != packageName && !isInputMethodPackage(p)) return r
                r.recycle()
            }
        }
        runCatching {
            val wins = windows ?: return@runCatching
            val ordered = wins.sortedByDescending { w ->
                when {
                    w.isFocused -> 3
                    w.isActive -> 2
                    else -> 1
                }
            }
            for (w in ordered) {
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p == packageName || isInputMethodPackage(p)) {
                    r.recycle()
                    continue
                }
                return r
            }
        }
        return null
    }

    /** 跨窗口取当前输入焦点的可编辑节点（AccessibilityService.findFocus 不局限于单个窗口树），返回 obtain 副本 */
    private fun findGlobalFocusedInput(): AccessibilityNodeInfo? {
        return try {
            val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            try {
                val p = f.packageName?.toString() ?: ""
                val ok = runCatching {
                    f.isEditable || f.className?.toString()?.endsWith("EditText") == true
                }.getOrDefault(false)
                if (ok && p != packageName && !isInputMethodPackage(p)) {
                    AccessibilityNodeInfo.obtain(f)
                } else {
                    null
                }
            } finally {
                f.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun doProcess(isTextChange: Boolean, quietNoInput: Boolean = false) {
        // 功能总开关：每次都从 SharedPreferences 读取，确保下拉栏磁贴切换后立即生效
        val funcOn = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
            .getBoolean("func_enabled", true)
        if (!funcOn) {
            // 只在从开到关的瞬间打一次日志，之后关闭状态下不再刷屏
            if (lastFuncOnState) {
                addLog("功能开关已关闭，跳过替换")
                lastFuncOnState = false
            }
            return
        }
        lastFuncOnState = true
        // 消费粘贴识别标记：本次变化若是粘贴，标点触发+分句同开时临时按分句处理（随后自动恢复）
        val pasteLike = lastPasteLike
        lastPasteLike = false
        if (processing) return
        processing = true
        substringHitForRun = false
        processingStart = System.currentTimeMillis()
        mainHandler.postDelayed(watchdogTask, 5000L)
        try {
            // 跨窗口获取宿主输入框所在窗口根节点：HyperOS/MIUI 打字时输入法会抢占 active window，
            // rootInActiveWindow 可能取到输入法窗口或 null，故用交互式窗口兜底
            val root = acquireRootForInput() ?: return
            // 双重屏蔽本应用：事件包名可能是输入法（如 com.baidu.input_vivo），绕过外层检查；
            // 这里用当前激活窗口的包名再判断一次，避免对言出化喵自身设置页面的输入框生效。
            if (root.packageName == packageName) return
            try {
                // 窗口树内找不到时，再用跨窗口全局输入焦点兜底
                val inputNode = findInputField(root) ?: findGlobalFocusedInput() ?: run {
                    if (!quietNoInput) addLog("未找到输入框（root=$root）")
                    if (isTextChange) countInputFailure()
                    return
                }
                try {
                    var raw = inputNode.text?.toString() ?: ""
                    if (!quietNoInput || raw != lastSet) {
                        addLog("找到输入框：raw=\"$raw\" editable=${inputNode.isEditable} cls=${inputNode.className} focused=${inputNode.isFocused}")
                    }
                    if (preCheckSkip(raw, inputNode, isTextChange, pasteLike)) return
                    // === 核心处理：统一调用 transformFloat（与悬浮窗同一套逻辑，避免两套逻辑不一致导致bug）===
                    // doProcess 只负责读文本、写文本、光标恢复；替换/附加/去重/单行模式/增量还原全部走 transformFloat
                    // isDeleting 判断：只有 raw 是 lastSet 的前缀时才认为是用户在删除文字；
                    // 如果 raw 比 lastSet 短但不是前缀，说明是清空后重新输入（或 lastSet 是残留的占位文本），重置 lastSet 为空
                    var isDeleting = false
                    if (lastSet.isNotEmpty() && raw.length < lastSet.length) {
                        if (lastSet.startsWith(raw)) {
                            isDeleting = true
                        } else {
                            lastSet = ""
                            userOriginal = ""
                        }
                    }
                    val (newText, cursorIndex) = PhraseProcessor.transformFloat(raw, lastSet, isDeleting)

                    if (newText == raw) {
                        addLog("无需变更：处理结果与输入一致（transformFloat）")
                        lastSet = raw
                        return
                    }

                    if (setText(inputNode, newText)) {
                        lastSet = newText
                        setCursorEnd(inputNode, cursorIndex)
                        // 记录写入成功：连续成功达到阈值自动把该应用加入不弹窗列表
                        val autoAdded = OverlayPrefs.recordWriteSuccess(this, lastEventPkg)
                        if (autoAdded) addLog("已自动加入不弹窗列表：$lastEventPkg（无障碍写入正常）")
                        addLog("替换成功：\"${raw.take(20)}\" -> \"${newText.take(20)}\"")
                        // 写入验证：部分应用 setText 返回成功但实际不生效（拦截写入），延迟重读校验
                        scheduleWriteVerify(newText)
                        // 光标双保险：QQ 等多行文本（如链接+换行）setText 后异步刷新/输入法联动会把光标重置回文本末尾，
                        // 立即 ACTION_SET_SELECTION 会被覆盖——延迟 250ms 重设一次（仅当文本未被用户继续编辑时），
                        // 确保光标停在句尾附加文字之前（"你好喵" → 光标在"喵"前，后续输入插入喵前、喵钉句尾）
                        val fixIndex = cursorIndex
                        val fixText = newText
                        mainHandler.postDelayed({
                            try {
                                val cur = rootInActiveWindow ?: return@postDelayed
                                val node = findInputField(cur) ?: return@postDelayed
                                if (node.text?.toString() == fixText) setCursorEnd(node, fixIndex)
                                node.recycle()
                            } catch (_: Exception) {
                            }
                        }, 250L)
                    } else {
                        // 写入被拦截：重置该应用的写入成功计数
                        OverlayPrefs.resetWriteSuccessCount(this, lastEventPkg)
                        // 不弹窗列表、关闭后冷却、或悬浮窗处于收起状态时不自动弹出悬浮窗
                        if (OverlayPrefs.isNoPopupPackage(this, lastEventPkg)) {
                            addLog("写入失败但应用在不弹窗列表中，不自动弹出悬浮窗：$lastEventPkg")
                        } else if (OverlayPrefs.isInCooldown(this)) {
                            addLog("写入失败但悬浮窗在关闭后冷却时间内，不自动弹出")
                        } else if (OverlayInput.isCollapsed()) {
                            addLog("写入失败但悬浮窗处于收起状态，不自动弹出悬浮窗")
                        } else {
                            // 自动启用全局悬浮窗作为替代输入通道（与手动打开同一实例，避免重复）
                            addLog("写入文本失败，启用悬浮窗输入")
                            try {
                                OverlayInput.show(this)
                            } catch (e: Exception) {
                                addLog("悬浮窗启动失败：${e.message}")
                            }
                        }
                    }
                } finally {
                    inputNode.recycle()
                }
            } finally {
                root.recycle()
            }
        } finally {
            processing = false
            mainHandler.removeCallbacks(watchdogTask)
        }
    }

    /**
     * 前置过滤链：返回 true 表示应跳过本次处理（doProcess 直接 return）。
     * 依次检查：占位提示文本 → 非聚焦输入框 → 空文本 → 窗口切换多行同步 → 归一化回显 → 后缀被误删恢复。
     * 所有副作用（状态更新、日志、setText 恢复）均在此方法内完成。
     */
    private fun preCheckSkip(
        raw: String,
        inputNode: AccessibilityNodeInfo,
        isTextChange: Boolean,
        pasteLike: Boolean
    ): Boolean {
        // 占位提示文本（placeholder/hint）过滤
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            runCatching { inputNode.hintText?.toString() }.getOrNull()
        } else null
        if (raw.isNotEmpty() && !hint.isNullOrEmpty() && raw.trim() == hint.trim()) {
            addLog("跳过：占位提示文本（hint=\"$hint\"），不处理")
            return true
        }
        // 非聚焦输入框不触发变语
        if (raw.isNotEmpty() && !inputNode.isFocused && (isTextChange || !isQQ)) {
            addLog("跳过：非聚焦输入框（隐藏/占位文本），不处理（raw=\"${raw.take(20)}\"）")
            return true
        }
        // 空文本：重置状态
        if (raw.isEmpty()) {
            if (isTextChange && (!inputNode.isEditable || !inputNode.isFocused)) {
                countInputFailure()
            }
            userOriginal = ""
            lastSet = ""
            tailPending = false
            return true
        }
        // 窗口切换后首次处理：已有多行文本只同步状态不处理；粘贴事件不受拦截
        if (justSwitchedWindow && lastSet.isEmpty() && raw.contains("\n") && !pasteLike) {
            justSwitchedWindow = false
            lastSet = raw
            userOriginal = raw
            addLog("窗口切换后同步：已有多行文本，仅同步状态不处理")
            return true
        }
        justSwitchedWindow = false
        // 归一化回显：内容仅后缀差异，无实质变化
        if (lastSet.isNotEmpty() && raw != lastSet) {
            val rawNorm = restoreUserInput(raw)
            val lastNorm = restoreUserInput(lastSet)
            if (rawNorm == lastNorm && rawNorm.isNotEmpty()) {
                lastSet = raw
                userOriginal = rawNorm
                addLog("跳过：归一化回显（内容无实质变化，仅后缀差异），同步状态")
                return true
            }
        }
        // 后缀被误删恢复：逐行比较，raw 是 lastSet 剥掉句尾后缀后的结果则重新写入
        if (lastSet.isNotEmpty() && raw.length < lastSet.length && activeSuffixes.isNotEmpty()) {
            val rawLines = raw.split("\n")
            val lastLines = lastSet.split("\n")
            if (rawLines.size == lastLines.size) {
                var allMatch = true
                for (i in rawLines.indices) {
                    if (PhraseProcessor.stripSuffixAll(lastLines[i]) != rawLines[i]) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    setText(inputNode, lastSet)
                    addLog("恢复：句尾后缀被输入法误删，重新写入完整内容")
                    return true
                }
            }
        }
        return false
    }

    private fun isSendButton(event: AccessibilityEvent): Boolean {
        val node = event.source ?: return false
        return try {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            val label = text + desc
            // 按钮文字/描述含"发送/send"，或资源 id 是 send 系列（兼容新版 QQ 的可访问性标签差异）
            label.contains("发送") || label.contains("send", true) ||
                id.contains("send", true) || cls.contains("Button")
        } catch (_: Exception) {
            false
        }
    }

    // 句末保留标点：标点触发模式下保留并移到句末（后缀之后），如"你好！" -> "你好喵~！"
    // 3.0 起统一收口到 PhraseProcessor
    private val KEEP_MARKS: String get() = PhraseProcessor.KEEP_MARKS

    // 把含替换结果和尾巴的当前文本还原为接近用户原始输入（逻辑在 PhraseProcessor）
    private fun restoreUserInput(text: String): String = PhraseProcessor.restoreUserInput(text)

    private fun pickSuffix(): String = PhraseProcessor.pickSuffix()

    // 输入框读取失败计数：连续 1 次（30 秒内）文本变化读不到有效输入框 → 启用全局悬浮窗（3.x 统一一套，非无障碍模式）
    private var inputFailureCount = 0
    private var lastInputFailureTime = 0L

    private fun countInputFailure() {
        val now = System.currentTimeMillis()
        if (now - lastInputFailureTime > 30000L) inputFailureCount = 0
        lastInputFailureTime = now
        inputFailureCount++
        if (inputFailureCount >= 1) {
            inputFailureCount = 0
            if (OverlayPrefs.isNoPopupPackage(this, lastEventPkg)) {
                addLog("连续多次无法读取输入框，但应用在不弹窗列表中，不自动弹出悬浮窗：$lastEventPkg")
            } else if (OverlayPrefs.isInCooldown(this)) {
                addLog("连续多次无法读取输入框，但悬浮窗在关闭后冷却时间内，不自动弹出")
            } else if (OverlayInput.isCollapsed()) {
                addLog("连续多次无法读取输入框，但悬浮窗处于收起状态，不自动弹出悬浮窗")
            } else {
                addLog("连续多次无法读取输入框，自动启用悬浮窗输入")
                try {
                    OverlayInput.show(this)
                } catch (e: Exception) {
                    addLog("悬浮窗启动失败：${e.message}")
                }
            }
        }
    }

    /** 分句触发文字（逻辑在 PhraseProcessor，含动态屏蔽符号） */
    private fun applySentenceSuffix(body: String): String = PhraseProcessor.applySentenceSuffix(body)

    /** 文本是否以句尾标点结尾（标点触发模式以此判断何时执行替换） */
    private fun endsWithSentencePunct(text: String): Boolean = PhraseProcessor.endsWithSentencePunct(text)

    /**
     * 标点触发模式下是否应处理当前文本（逻辑在 PhraseProcessor）：
     *  - 以句尾标点结尾（。！？!?…～~，）
     *  - 以换行符结尾（用户用换行分隔长句）
     */
    private fun shouldTriggerPunct(text: String): Boolean = PhraseProcessor.shouldTriggerPunct(text)

    /** 标点触发转换（逻辑在 PhraseProcessor，统一模型） */
    private fun transformPunctuation(raw: String): PhraseProcessor.PunctTransformResult = PhraseProcessor.transformPunctuation(raw)

    /** 判断字符串是否全部由标点符号组成（空串返回 false；符号集来自标点屏蔽框） */
    private fun isPunctOnly(s: String): Boolean = PhraseProcessor.isPunctOnly(s)

    /** 剥掉文本末尾一个已附加的句尾后缀（按后缀长度从长到短匹配），无匹配返回原文本 */
    private fun stripSuffix(text: String): String = PhraseProcessor.stripSuffix(text)

    private fun substringRuleMatch(text: String): Boolean = PhraseProcessor.substringRuleMatch(text)

    private fun applyRulesNoSuffix(text: String, skipTailExclude: Boolean = false): Pair<String, Boolean> =
        PhraseProcessor.applyRulesNoSuffix(text, skipTailExclude)

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 写入验证：部分应用 setText 返回成功但实际不生效（拦截无障碍写入，如微信/自绘控件），
     * 延迟 250ms 重读输入框文本校验，不一致则判定被拦截并自动启用悬浮窗输入。
     * 正常应用 setText 后会产生文本变化回显事件（在事件处理里把 verifyPending 置 false），验证自动跳过。
     */
    private fun scheduleWriteVerify(expected: String) {
        try {
            verifyPending = true
            val r = Runnable {
                if (!verifyPending) return@Runnable
                verifyPending = false
                try {
                    val root = rootInActiveWindow ?: return@Runnable
                    val n = findInputField(root)
                    if (n == null) {
                        root.recycle()
                        return@Runnable
                    }
                    val cur = n.text?.toString() ?: ""
                    n.recycle()
                    root.recycle()
                    if (cur != expected) {
                        OverlayPrefs.resetWriteSuccessCount(this@AutomationBridge, lastEventPkg)
                        if (OverlayPrefs.isNoPopupPackage(this@AutomationBridge, lastEventPkg)) {
                            addLog("写入验证失败但应用在不弹窗列表中，不自动弹出悬浮窗：$lastEventPkg")
                        } else if (OverlayPrefs.isInCooldown(this@AutomationBridge)) {
                            addLog("写入验证失败但悬浮窗在关闭后冷却时间内，不自动弹出")
                        } else {
                            addLog("写入验证失败（期望=$expected 实际=$cur），启用悬浮窗输入")
                            OverlayInput.show(this@AutomationBridge)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            mainHandler.postDelayed(r, 250L)
        } catch (_: Exception) {
        }
    }

    /** 把光标定位到文本框指定索引处 */
    /**
     * 计算 index 所在行的行末位置（下一个换行符之前，或文本末尾）。
     * 用于多行文本：把光标移到"当前行末"而非"整个文本末尾"。
     */
    private fun lineEndIndex(text: String, index: Int): Int {
        val nl = text.indexOf('\n', index)
        return if (nl >= 0) nl else text.length
    }

    private fun setCursorEnd(node: AccessibilityNodeInfo, index: Int) {
        try {
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, index)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, index)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        } catch (_: Exception) {
        }
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 仅 QQ 系列使用专属 viewId 优先定位；其它应用没有这些 id，直接走通用可编辑框查找
        if (isQQ) {
            val ids = listOf(
                QQ_ID_PREFIX + "inputBar",
                QQ_ID_PREFIX + "chat_input",
                QQ_ID_PREFIX + "message_edit",
                QQ_ID_PREFIX + "sendMessageEditText",
                QQ_ID_PREFIX + "msg_input_et",
                QQ_ID_PREFIX + "footer_et_msg",
                QQ_ID_PREFIX + "input_edit",
                QQ_ID_PREFIX + "input",
                QQ_ID_PREFIX + "edtInput",
                QQ_ID_PREFIX + "et_input",
                QQ_ID_PREFIX + "inputEdt",
                QQ_ID_PREFIX + "inputbar",
                QQ2_ID_PREFIX + "input",
                QQ2_ID_PREFIX + "edtInput"
            )

            for (id in ids) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    if (!nodes.isNullOrEmpty()) {
                        for (n in nodes) {
                            try {
                                if (n.isEditable) {
                                    // 返回一个副本，避免 finally 里回收后返回已回收的节点
                                    return AccessibilityNodeInfo.obtain(n)
                                }
                            } finally {
                                n.recycle()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "查找ID $id 出错: " + e.message)
                }
            }
        }

        // 通用查找：先找聚焦的可编辑节点（多输入框页面避免读到非聚焦框，如豆包搜索框）
        findFocusedEditable(root)?.let { return it }
        // 再按 isEditable 标记查找（大多数 app 有效）
        findEditable(root)?.let { return it }
        // 兜底：部分应用（如微信）不报告 isEditable，按类名查找 EditText 控件
        findEditTextByClassName(root)?.let { return it }
        // 最后兜底：整树对无障碍隐身（如微信）时，直接取当前输入焦点的节点；
        // FOCUS_INPUT 不过滤可见性，绕过树被隐藏的问题；
        // 校验其确为可编辑输入框，否则视为未找到（让上层走"读取失败→悬浮窗"逻辑）
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusOk = focus != null && runCatching {
            focus.isEditable || (focus.className?.toString()?.endsWith("EditText") == true)
        }.getOrDefault(false)
        return if (focusOk) AccessibilityNodeInfo.obtain(focus) else {
            focus?.recycle()
            null
        }
    }

    /** 按类名深度优先查找第一个"聚焦且可编辑"的节点（多输入框页面优先取正在输入的框） */
    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isEditable && node.isFocused) return AccessibilityNodeInfo.obtain(node)
        } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findFocusedEditable(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 按类名深度优先查找第一个 EditText（含自定义子类，如微信的输入框） */
    private fun findEditTextByClassName(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            val cls = node.className?.toString() ?: ""
            if (cls.isNotEmpty() && cls.endsWith("EditText")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findEditTextByClassName(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findEditable(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun showToast(text: String) {
        mainHandler.post { Toast.makeText(this@AutomationBridge, text, Toast.LENGTH_LONG).show() }
    }

    // 按 minIntervalMs 节流打印，避免刷屏
    private var lastThrottleLog: Long = 0
    private fun logThrottled(msg: String, minIntervalMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastThrottleLog >= minIntervalMs) {
            lastThrottleLog = now
            addLog(msg)
        }
    }

    override fun onInterrupt() {
        addLog("onInterrupt（服务被系统中断）")
        Log.d(TAG, "onInterrupt")
    }

    override fun onGesture(gestureId: Int): Boolean = false
}
