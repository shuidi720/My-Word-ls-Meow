package io.catpaw.kittytalk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置 Tab：导入导出、版权声明、电池设置、悬浮窗、不弹窗列表、主题、彩蛋
 * 注意：thanksClickCount / lastThanksClick 字段留在 LauncherActivity 中
 */

internal fun LauncherActivity.copyText(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("nhy", text))
}

internal fun LauncherActivity.dp2(v: Int): Int = Math.round(v * resources.displayMetrics.density)

/** 跳转本应用的系统耗电 / 后台设置（按主流机型尝试，失败退回通用电池优化列表） */
internal fun LauncherActivity.openBatterySettings() {
    val intents = listOf(
        Intent().setComponent(
            android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
        Intent().setComponent(
            android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.BatteryOptimization")),
        Intent().setComponent(
            android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Intent().setComponent(
            android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(
            android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )
    for (it in intents) {
        try {
            startActivity(it)
            return
        } catch (_: Exception) {}
    }
    toast("未能打开耗电设置，请手动在系统设置中查找")
}

/** 不弹窗列表管理对话框：显示已加入的应用，支持删除和手动添加 */
internal fun LauncherActivity.showNoPopupListDialog() {
    val packages = OverlayPrefs.getNoPopupPackages(this).sorted()
    val message = if (packages.isEmpty()) {
        "当前没有应用在不弹窗列表中。\n\n在应用里无障碍写入成功 1 次后会自动加入；也可手动输入包名添加。"
    } else {
        "已加入不弹窗列表的应用（点击删除）：\n\n" + packages.joinToString("\n")
    }
    val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("不弹窗列表管理")
        .setMessage(message)
        .setPositiveButton("手动添加") { _, _ ->
            val input = android.widget.EditText(this).apply {
                hint = "输入应用包名，如 com.tencent.mobileqq"
                setPadding(dp2(16), dp2(12), dp2(16), dp2(12))
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("手动添加到不弹窗列表")
                .setView(input)
                .setPositiveButton("添加") { _, _ ->
                    val pkg = input.text?.toString()?.trim() ?: ""
                    if (pkg.isNotBlank()) {
                        OverlayPrefs.addNoPopupPackage(this, pkg)
                        toast("已添加：$pkg")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        .setNegativeButton("关闭", null)
    if (packages.isNotEmpty()) {
        dialog.setNeutralButton("删除选中") { _, _ ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择要删除的应用")
                .setItems(packages.toTypedArray()) { _, which ->
                    val pkg = packages[which]
                    OverlayPrefs.removeNoPopupPackage(this, pkg)
                    toast("已删除：$pkg")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    dialog.show()
}

internal fun LauncherActivity.setupSettingsButtons() {
    binding.tabSettings.exportRuleButton.setOnClickListener {
        showExportSelectionDialog()
    }
    binding.tabSettings.importRuleButton.setOnClickListener {
        importLauncher.launch(arrayOf("text/plain", "application/json", "*/*"))
    }
    binding.tabSettings.resetRulesButton.setOnClickListener {
        MaterialAlertDialogBuilder(this)
            .setTitle("重置规则")
            .setMessage("确定将所有分类、规则与附加文字恢复到默认状态吗？")
            .setPositiveButton("重置") { _, _ ->
                cfg = SettingsRepository.resetConfig()
                applyConfig()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    binding.tabSettings.projectLink.setOnClickListener {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sehhan667/QQNHYhelper")))
        } catch (e: Exception) {
            toast("无法打开链接")
        }
    }
    binding.tabSettings.originalAuthorText.setOnClickListener {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://b23.tv/45MtN3q")))
        } catch (e: Exception) {
            toast("无法打开链接")
        }
    }
    binding.tabSettings.qqGroupText.setOnClickListener {
        MaterialAlertDialogBuilder(this)
            .setTitle("QQ群")
            .setMessage("QQ群：1106459724\n验证密码：NHYhelper")
            .setPositiveButton("一键复制QQ群号") { _, _ ->
                copyText("1106459724")
                toast("已复制QQ群号：1106459724")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    binding.tabSettings.mitLicenseText.setOnClickListener {
        MaterialAlertDialogBuilder(this)
            .setTitle("版权与许可声明")
            .setMessage(
                "本应用基于 MIT 协议的开源项目 QQNHYhelper 衍生开发，按协议要求保留其版权与许可声明。\n\n" +
                    "———————————————\n" +
                    "MIT License\n\n" +
                    "Copyright (c) 2026 qaqdym, AnotherCream (QQNHYhelper)\n" +
                    "Copyright (c) 2026 半非半欧是个屑233 (言出化喵)\n\n" +
                    "Permission is hereby granted, free of charge, to any person obtaining a copy " +
                    "of this software and associated documentation files (the “Software”), to deal " +
                    "in the Software without restriction, including without limitation the rights " +
                    "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
                    "copies of the Software, and to permit persons to whom the Software is " +
                    "furnished to do so, subject to the following conditions:\n\n" +
                    "The above copyright notice and this permission notice shall be included in all " +
                    "copies or substantial portions of the Software.\n\n" +
                    "THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
                    "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
                    "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
                    "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
                    "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
                    "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
                    "SOFTWARE."
            )
            .setPositiveButton("关闭", null)
            .show()
    }
    binding.tabSettings.batteryJumpButton.setOnClickListener { openBatterySettings() }
    binding.tabSettings.restrictedSettingsButton.setOnClickListener {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            toast("请点击右上角三个点解除，或在权限管理内右上角的三个点解除")
        } catch (_: Exception) {
            toast("未能打开应用详情，请手动到 设置→应用→言出化喵 中操作")
        }
    }
    binding.tabSettings.showFloatButton.setOnClickListener {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授权悬浮窗权限，再打开悬浮窗")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        } else {
            OverlayInput.show(this, manual = true)
        }
    }
    binding.tabSettings.noPopupListButton.setOnClickListener { showNoPopupListDialog() }
    val collapseSwitch = binding.tabSettings.floatCollapseSwitch
    collapseSwitch.isChecked = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
        .getBoolean("float_collapse_on_copy", true)
    collapseSwitch.setOnCheckedChangeListener { _, checked ->
        getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("float_collapse_on_copy", checked).apply()
        AutomationBridge.instance?.let { it.floatCollapseOnCopy = checked }
    }
    binding.tabSettings.themeCard.setOnClickListener {
        themeLauncher.launch(Intent(this, AppearanceActivity::class.java))
    }
    val logVisible = binding.tabSettings.logVisibleSwitch
    logVisible.isChecked = isLogVisible()
    logVisible.setOnCheckedChangeListener { _, checked ->
        saveLogVisible(checked)
        binding.bottomNav.menu.findItem(R.id.nav_logs).isVisible = checked
        if (!checked && binding.bottomNav.selectedItemId == R.id.nav_logs) {
            showTab(R.id.nav_home)
        }
    }
    binding.tabSettings.thanksText.setOnClickListener {
        val now = System.currentTimeMillis()
        if (now - lastThanksClick > 2000) thanksClickCount = 0
        lastThanksClick = now
        thanksClickCount++
        if (thanksClickCount >= 8) {
            thanksClickCount = 0
            val density = resources.displayMetrics.density
            val img = ImageView(this).apply {
                setImageResource(R.drawable.easter_egg)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxWidth = (640 * density).toInt()
                maxHeight = (640 * density).toInt()
                setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            }
            val msg = TextView(this).apply {
                text = "其实nhy是我的一个朋友，为了模仿他平时的魔怔发言才做了这个魔改版awa"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding((20 * density).toInt(), (4 * density).toInt(), (20 * density).toInt(), (20 * density).toInt())
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(img)
                addView(msg)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("🎉 魔改版彩蛋")
                .setView(column)
                .setPositiveButton("关闭", null)
                .setNeutralButton("？？？") { _, _ -> addNhyPresetFromEasterEgg() }
                .show()
        }
    }
}
