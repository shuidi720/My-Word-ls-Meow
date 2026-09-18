package io.catpaw.kittytalk

import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku

/**
 * 主页 Tab：服务状态、功能开关、标点触发、分句触发、Shizuku 保活、版本徽章
 */

internal fun LauncherActivity.setupInfoButton() {
    binding.tabMain.infoButton.setOnClickListener {
        MaterialAlertDialogBuilder(this)
            .setTitle("关于本软件")
            .setMessage(
                "【功能介绍】\n" +
                    "· 在任意应用的输入框中实时改写文本（不仅限 QQ）\n" +
                    "· 子串替换 / 整句替换：关键词与整句两级规则，支持去重\n" +
                    "· 句尾附加：多条后缀加权随机附加，自动避免重复堆叠\n" +
                    "· 标点触发：只在句尾输入标点 / 换行时才替换，可开启“句号触发后消失”\n" +
                    "· 粘贴分句触发：长文本粘贴时按分句附加，正常打字不触发\n" +
                    "· 内置猫娘等预设口癖，规则可分类编辑、一键切换，支持导入导出\n" +
                    "· 多应用白名单与功能总开关，下拉通知栏提供快捷开关磁贴\n" +
                    "· 悬浮窗备用入口：无障碍写入被拦截时仍可在悬浮窗内打字变语\n" +
                    "· Shizuku 保活（可选）：一键加入系统后台白名单，降低被杀概率\n" +
                    "· 完全离线：不申请联网权限，不收集、不上传任何信息\n\n" +
                    "【使用前提】\n" +
                    "使用前需先在系统「无障碍」中开启本服务的开关。\n\n" +
                    "【额外声明】\n" +
                    "在 QQ 里直接安装本软件会导致无障碍功能无法启动。请卸载后在其他应用（如文件管理）中重新安装。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }
}

internal fun LauncherActivity.setupServiceCard() {
    binding.tabMain.serviceCard.setOnClickListener {
        openAccessibilitySettings()
    }
}

internal fun LauncherActivity.setupFuncSwitch() {
    binding.tabMain.funcSwitch.isChecked = isFuncEnabled()
    updateFuncStatus()
    binding.tabMain.funcSwitch.setOnCheckedChangeListener { _, checked ->
        darkPrefs().edit().putBoolean(funcKey, checked).apply()
        AutomationBridge.instance?.setFuncEnabled(checked)
        updateFuncStatus()
    }
}

internal fun LauncherActivity.isFuncEnabled(): Boolean =
    darkPrefs().getBoolean(funcKey, true)

/** 主页"标点触发"开关：开启后服务只在输入句尾标点时整句替换 */
internal fun LauncherActivity.setupPunctuationSwitch() {
    binding.tabMain.punctSwitch.isChecked = darkPrefs().getBoolean(punctKey, false)
    binding.tabMain.punctSwitch.setOnCheckedChangeListener { _, checked ->
        darkPrefs().edit().putBoolean(punctKey, checked).apply()
        AutomationBridge.instance?.reload()
        PhraseProcessor.reload(this)
        updatePunctReplaceVisibility(checked)
    }
    binding.tabMain.punctEatPeriodSwitch.isChecked = darkPrefs().getBoolean(punctEatPeriodKey, false)
    binding.tabMain.punctEatPeriodSwitch.setOnCheckedChangeListener { _, checked ->
        darkPrefs().edit().putBoolean(punctEatPeriodKey, checked).apply()
        AutomationBridge.instance?.reload()
        PhraseProcessor.reload(this)
    }
    updatePunctReplaceVisibility(binding.tabMain.punctSwitch.isChecked)
}

/** 标点触发开关关闭时隐藏句号消失开关 */
internal fun LauncherActivity.updatePunctReplaceVisibility(on: Boolean) {
    val v = if (on) android.view.View.VISIBLE else android.view.View.GONE
    binding.tabMain.punctEatPeriodRow.visibility = v
}

/** 分句触发文字开关：开启后即时生效（服务 reload） */
internal fun LauncherActivity.setupSentenceSuffixSwitch() {
    binding.tabMain.sentenceTriggerSwitch.isChecked = darkPrefs().getBoolean(sentenceTriggerKey, false)
    binding.tabMain.sentenceTriggerSwitch.setOnCheckedChangeListener { _, checked ->
        darkPrefs().edit().putBoolean(sentenceTriggerKey, checked).apply()
        AutomationBridge.instance?.reload()
        PhraseProcessor.reload(this)
    }
    val userBlockChars = darkPrefs().getString(sentenceBlockKey, null).orEmpty().replace(" ", "")
    binding.tabMain.sentenceBlockInput.setText(userBlockChars)
    binding.tabMain.sentenceBlockInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val v = (s?.toString() ?: "").replace(" ", "")
            if (darkPrefs().getString(sentenceBlockKey, null) != v) {
                darkPrefs().edit().putString(sentenceBlockKey, v).apply()
                AutomationBridge.instance?.reload()
                PhraseProcessor.reload(this@setupSentenceSuffixSwitch)
            }
        }
    })
}

internal fun LauncherActivity.updateFuncStatus() {
    val on = isFuncEnabled()
    binding.tabMain.funcSwitch.isChecked = on
    binding.tabMain.funcStatus.text = if (on) "替换功能已开启" else "替换功能已关闭"
}

internal fun LauncherActivity.updateServiceStatus() {
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val isEnabled = am.isEnabled && AutomationBridge.instance != null
    binding.tabMain.serviceStatus.text = if (isEnabled) "已开启" else "未开启"
    updateFuncStatus()
}

internal fun LauncherActivity.openAccessibilitySettings() {
    try {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

// ---------- Shizuku 保活 ----------

internal fun LauncherActivity.setupShizuku() {
    binding.tabSettings.shizukuSwitch.isChecked = darkPrefs().getBoolean(shizukuKey, false)
    binding.tabSettings.shizukuSwitch.setOnCheckedChangeListener { _, checked ->
        darkPrefs().edit().putBoolean(shizukuKey, checked).apply()
        AutomationBridge.instance?.reload()
        if (checked) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
            LifelineService.start(this)
            if (PrivilegeBridge.isAvailable() && PrivilegeBridge.isGranted()) {
                applyKeepAliveNow()
            }
        } else {
            LifelineService.stop(this)
        }
        refreshShizukuStatus()
    }
    binding.tabSettings.shizukuGrantButton.setOnClickListener {
        when {
            !PrivilegeBridge.isAvailable() ->
                toast("Shizuku 未连接：请安装 Shizuku 并通过 ADB/无线调试激活")
            PrivilegeBridge.isGranted() -> toast("Shizuku 已授权")
            else -> PrivilegeBridge.requestPermission()
        }
    }
    binding.tabSettings.shizukuApplyButton.setOnClickListener { applyKeepAliveNow() }
    try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
    PrivilegeBridge.addBinderListener(shizukuBinderListener)
    refreshShizukuStatus()
    if (darkPrefs().getBoolean(shizukuKey, false) &&
        PrivilegeBridge.isAvailable() && PrivilegeBridge.isGranted()
    ) {
        LifelineService.start(this)
        applyKeepAliveNow()
    }
}

internal fun LauncherActivity.refreshShizukuStatus() {
    val status = binding.tabSettings.shizukuStatus
    status.text = when {
        !PrivilegeBridge.isAvailable() -> "Shizuku 未连接：请安装 Shizuku 并通过 ADB/无线调试激活"
        !PrivilegeBridge.isGranted() -> "Shizuku 已连接，未授权：点击「申请授权」"
        else -> "Shizuku 已授权，可在后台执行系统级保活"
    }
}

internal fun LauncherActivity.applyKeepAliveNow() {
    if (!PrivilegeBridge.isAvailable()) {
        toast("Shizuku 未连接")
        return
    }
    if (!PrivilegeBridge.isGranted()) {
        toast("Shizuku 未授权，请先申请授权")
        return
    }
    binding.tabSettings.shizukuApplyButton.isEnabled = false
    binding.tabSettings.shizukuApplyButton.text = "应用中..."
    Thread {
        try {
            val result = PrivilegeBridge.applyKeepAlive(this)
            val status = PrivilegeBridge.queryStatus(this)
            runOnUiThread {
                binding.tabSettings.shizukuApplyButton.isEnabled = true
                binding.tabSettings.shizukuApplyButton.text = "立即应用白名单"
                binding.tabSettings.shizukuStatus.text = status
                toast("系统白名单已应用")
                AutomationBridge.addLog("Shizuku 白名单：\n$result\n$status")
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.tabSettings.shizukuApplyButton.isEnabled = true
                binding.tabSettings.shizukuApplyButton.text = "立即应用白名单"
                toast("执行失败：${e.message}")
            }
        }
    }.start()
}
