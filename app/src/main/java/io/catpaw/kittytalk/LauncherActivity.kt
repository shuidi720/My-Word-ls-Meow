package io.catpaw.kittytalk

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.catpaw.kittytalk.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

/**
 * 主 Activity：仅负责生命周期、共享状态、页签切换。
 * 各 Tab 的 UI 逻辑已拆分到 tabs/ 目录下的扩展函数文件：
 *  - HomeTab.kt     主页（服务状态、功能开关、标点/分句触发、Shizuku 保活）
 *  - RulesTab.kt    替换规则（分类、子串/整句规则、句尾附加、导入导出）
 *  - WhitelistTab.kt 白名单（应用白名单管理、应用选择器）
 *  - LogsTab.kt     日志（日志显示、刷新、滚动跟随）
 *  - SettingsTab.kt 设置（版权、修复记录、电池、悬浮窗、彩蛋）
 */
class LauncherActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var cfg: SettingsRepository.Config

    // 列表默认显示条数
    internal val maxVisible = 3
    internal var suffixesExpanded = false
    internal var rulesExpanded = false
    internal var wholeRulesExpanded = false

    // 导出：写文本文件（仅导出用户勾选的分类）
    internal var exportTarget: List<Category>? = null
    internal val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    val src = exportTarget ?: cfg.categories
                    it.write(SettingsRepository.toExportJson(SettingsRepository.Config(src.toMutableList())).toByteArray())
                }
                toast("已导出规则")
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
            exportTarget = null
        }
    }

    // 导入：读文本文件
    internal val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                val imported = if (text != null) SettingsRepository.parseImport(text) else null
                if (imported == null) {
                    toast("导入失败：文件格式无效")
                } else {
                    mergeImport(imported)
                }
            } catch (e: Exception) {
                toast("导入失败：" + e.message)
            }
        }
    }

    // 主题页：返回后 recreate 以应用新主题
    internal val themeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PaletteStore.load(this).version != lastThemeVersion) {
            recreate()
        }
    }

    // ----------  pref key（各 Tab 共享） ----------
    internal val funcKey = "func_enabled"
    internal val punctKey = "punctuation_trigger"
    internal val punctEatPeriodKey = "punct_eat_period"
    internal val sentenceBlockKey = "sentence_block_chars"
    internal val sentenceTriggerKey = "sentence_trigger"
    internal val shizukuKey = "shizuku_keepalive"
    internal val prefWarningVersion = "warning_version"
    internal val prefLogVisible = "log_visible"
    internal val prefCurrentTab = "current_tab"

    // ---------- Shizuku 监听器（HomeTab 使用） ----------
    internal val shizukuListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == PrivilegeBridge.REQUEST_CODE) {
                runOnUiThread { refreshShizukuStatus() }
            }
        }
    }
    internal val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuStatus() }
    }

    // ---------- 日志跟随状态（LogsTab 使用） ----------
    internal var logFollowBottom = true
    internal var logProgrammaticScroll = false

    // ---------- 彩蛋计数（SettingsTab 使用） ----------
    internal var thanksClickCount = 0
    internal var lastThanksClick = 0L

    internal var lastThemeVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeCfg = PaletteStore.load(this)
        AppCompatDelegate.setDefaultNightMode(PaletteStore.appcompatNightMode(themeCfg.themeMode))
        setTheme(PaletteStore.themeStyleRes(themeCfg.palette))
        super.onCreate(savedInstanceState)
        if (themeCfg.palette == Palette.SYSTEM) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        lastThemeVersion = themeCfg.version

        cfg = SettingsRepository.load(this)
        setupBottomNav()
        setupServiceCard()
        setupFuncSwitch()
        setupPunctuationSwitch()
        setupSentenceSuffixSwitch()
        setupSuffixControls()
        setupCategoryControls()
        setupListToggles()
        setupWhitelist()
        setupFab()
        setupSettingsButtons()
        setupShizuku()
        setupDebugLog()
        setupInfoButton()
        setupVersionBadge()
        updateServiceStatus()
        updateThemeStatus()

        val savedTab = darkPrefs().getInt(prefCurrentTab, R.id.nav_home)
        val logItem = binding.bottomNav.menu.findItem(R.id.nav_logs)
        val restoreTab = if (savedTab == R.id.nav_logs && logItem != null && !logItem.isVisible) {
            R.id.nav_home
        } else savedTab
        showTab(restoreTab)
        maybeShowFirstWarning()
    }

    /** 每次升级到新版本都会弹出一次警告，至少等 5 秒倒计时结束才能关闭 */
    internal fun maybeShowFirstWarning() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        if (darkPrefs().getString(prefWarningVersion, "") == version) return

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("警告")
            .setMessage(
                "“言出化喵”改自QQNHY助手。“QQNHY助手”是参考LaiNova_制作的“QQ喵喵助手”重写的版本。" +
                    "使用软件需要无障碍权限，可能会被有心之人利用制作包含恶意代码的版本。" +
                    "请确保你安装的是来自官方渠道的原始版本。" +
                    "本软件完全离线运行，不会收集或上传任何个人信息，也不会访问网络。" +
                    "本软件不对一切使用后带来的后果负责。"
            )
            .setCancelable(false)
            .setPositiveButton("我已阅读并知晓（5）", null)
            .create()
        dialog.setOnShowListener {
            val btn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            var remain = 5
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    remain--
                    if (remain <= 0) {
                        btn.isEnabled = true
                        btn.text = "我已阅读并知晓"
                    } else {
                        btn.text = "我已阅读并知晓（$remain）"
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            handler.postDelayed(tick, 1000)
            btn.setOnClickListener {
                darkPrefs().edit().putString(prefWarningVersion, version).apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateThemeStatus()
    }

    internal fun updateThemeStatus() {
        val t = PaletteStore.load(this)
        binding.tabSettings.themeStatus.text =
            "${PaletteStore.modeLabel(t.themeMode)} / ${PaletteStore.paletteLabel(t.palette)}"
    }

    override fun onDestroy() {
        AutomationBridge.logListener = null
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
        PrivilegeBridge.removeBinderListener(shizukuBinderListener)
        super.onDestroy()
    }

    // ---------- 全面屏 / 系统栏适配 ----------

    internal fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    internal fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    // ---------- 页签 ----------

    internal fun setupBottomNav() {
        val item = binding.bottomNav.menu.findItem(R.id.nav_logs)
        item.isVisible = isLogVisible()
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
    }

    internal fun showTab(menuId: Int) {
        if (binding.bottomNav.selectedItemId != menuId) {
            binding.bottomNav.setOnItemSelectedListener(null)
            binding.bottomNav.selectedItemId = menuId
            binding.bottomNav.setOnItemSelectedListener { item ->
                showTab(item.itemId)
                true
            }
        }
        darkPrefs().edit().putInt(prefCurrentTab, menuId).apply()
        val main = menuId == R.id.nav_home
        val rules = menuId == R.id.nav_rules
        val whitelist = menuId == R.id.nav_whitelist
        val logs = menuId == R.id.nav_logs
        val settings = menuId == R.id.nav_settings
        binding.tabMain.root.visibility = if (main) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabRules.root.visibility = if (rules) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabWhitelist.root.visibility = if (whitelist) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabLogs.root.visibility = if (logs) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabSettings.root.visibility = if (settings) android.view.View.VISIBLE else android.view.View.GONE
        if (rules) refreshRulesUi()
        if (whitelist) refreshWhitelistUi()
        if (logs) refreshDebugLog()
        if (settings) refreshDebugLog()
    }

    // ---------- 通用 ----------

    internal fun darkPrefs() = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)

    internal fun setupVersionBadge() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.tabMain.versionBadge.text = "v${info.versionName}"
            binding.tabSettings.aboutAppVersion.text = "v ${info.versionName}"
        } catch (e: Exception) {
            binding.tabMain.versionBadge.text = "v1.0"
            binding.tabSettings.aboutAppVersion.text = "v 1.0"
        }
    }

    internal fun isLogVisible(): Boolean = darkPrefs().getBoolean(prefLogVisible, false)
    internal fun saveLogVisible(v: Boolean) = darkPrefs().edit().putBoolean(prefLogVisible, v).apply()

    internal fun applyConfig() {
        SettingsRepository.save(this, cfg)
        AutomationBridge.instance?.reload()
        refreshRulesUi()
    }

    internal fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
