package io.catpaw.kittytalk

import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 白名单 Tab：应用白名单管理、应用选择器
 */

internal class PickerItem(val pkg: String, val name: String)

internal class AppPickerAdapter(
    private val activity: LauncherActivity,
    internal val full: List<PickerItem>,
    internal val added: MutableSet<String>
) : android.widget.BaseAdapter() {

    internal var shown: List<PickerItem> = full

    fun filter(q: String) {
        shown = if (q.isBlank()) full
        else full.filter { it.name.contains(q, true) || it.pkg.contains(q, true) }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = shown.size
    override fun getItem(pos: Int): Any = shown[pos]
    override fun getItemId(pos: Int): Long = pos.toLong()

    override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_pick, parent, false)
        val item = shown[pos]

        val name = v.findViewById<TextView>(R.id.itemAppName)
        name.text = item.name
        val pkg = v.findViewById<TextView>(R.id.itemAppPkg)
        pkg.text = item.pkg

        val icon = v.findViewById<ImageView>(R.id.itemAppIcon)
        icon.setImageDrawable(
            try { activity.packageManager.getApplicationIcon(item.pkg) }
            catch (_: Throwable) { null }
        )

        val isAdded = item.pkg in added
        val add = v.findViewById<TextView>(R.id.itemAppAdd)
        add.text = if (isAdded) "✓" else "＋"
        add.alpha = if (isAdded) 0.6f else 1f
        v.setOnClickListener {
            if (item.pkg in added) {
                activity.toast("该应用已在白名单中")
            } else {
                activity.addAppFromPicker(item.pkg)
                added.add(item.pkg)
                notifyDataSetChanged()
            }
        }
        return v
    }
}

internal fun LauncherActivity.getWhitelist(): MutableList<AppEntry> = WhitelistStore.load(this)

internal fun LauncherActivity.saveWhitelist(list: List<AppEntry>) {
    WhitelistStore.save(this, list)
    AutomationBridge.instance?.reload()
}

internal fun LauncherActivity.setupWhitelist() {
    binding.tabWhitelist.allAppsSwitch.isChecked = darkPrefs().getBoolean("all_apps_enabled", false)
    binding.tabWhitelist.allAppsSwitch.setOnCheckedChangeListener { _, checked ->
        darkPrefs().edit().putBoolean("all_apps_enabled", checked).apply()
        AutomationBridge.instance?.reload()
    }
    binding.tabWhitelist.addAppButton.setOnClickListener {
        val name = binding.tabWhitelist.newAppInput.text?.toString()?.trim() ?: ""
        if (!isValidPackageName(name)) {
            toast("包名无效，请输入完整的应用包名")
            return@setOnClickListener
        }
        val list = getWhitelist()
        if (list.any { it.pkg == name }) {
            toast("该应用已在白名单中")
            return@setOnClickListener
        }
        list.add(AppEntry(name, true, false, ""))
        saveWhitelist(list)
        binding.tabWhitelist.newAppInput.setText("")
        refreshWhitelistUi()
    }
    binding.tabWhitelist.quickWechatButton.setOnClickListener {
        addQuickApp("com.tencent.mm", "微信")
    }
    binding.tabWhitelist.quickWeWorkButton.setOnClickListener {
        addQuickApp("com.tencent.wework", "企业微信")
    }
    binding.tabWhitelist.pickAppButton.setOnClickListener {
        showAppPickerDialog()
    }
}

internal fun LauncherActivity.addAppFromPicker(pkg: String) {
    val list = getWhitelist()
    if (list.any { it.pkg == pkg }) {
        toast("该应用已在白名单中")
        return
    }
    val label = resolveAppLabel(pkg).ifBlank { pkg }
    list.add(AppEntry(pkg, true, false, label))
    saveWhitelist(list)
    refreshWhitelistUi()
}

internal fun LauncherActivity.addQuickApp(pkg: String, label: String) {
    val list = getWhitelist()
    if (list.any { it.pkg == pkg }) {
        toast("$label 已在白名单中")
        return
    }
    list.add(AppEntry(pkg, true, false, label))
    saveWhitelist(list)
    refreshWhitelistUi()
}

internal fun LauncherActivity.isValidPackageName(s: String): Boolean =
    s.isNotEmpty() && s[0].isLetter() &&
        s.all { it.isLetterOrDigit() || it == '.' || it == '_' } &&
        !s.contains("..")

internal fun LauncherActivity.refreshWhitelistUi() {
    val list = getWhitelist()
    val container = binding.tabWhitelist.appListContainer
    container.removeAllViews()
    binding.tabWhitelist.emptyAppHint.visibility =
        if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    val inflater = LayoutInflater.from(this)
    for (entry in list) {
        container.addView(whitelistRow(inflater, container, entry))
    }
}

internal fun LauncherActivity.resolveAppLabel(pkg: String): String = try {
    val pm = packageManager
    val ai = pm.getApplicationInfo(pkg, 0)
    pm.getApplicationLabel(ai).toString()
} catch (_: Throwable) {
    ""
}

internal fun LauncherActivity.displayName(entry: AppEntry): String =
    if (entry.label.isNotBlank()) entry.label
    else resolveAppLabel(entry.pkg).ifBlank { entry.pkg }

internal fun LauncherActivity.whitelistRow(inflater: LayoutInflater, parent: android.view.ViewGroup, entry: AppEntry): android.view.View {
    val row = inflater.inflate(R.layout.view_whitelist_item, parent, false)
    val labelView = row.findViewById<TextView>(R.id.appLabel)
    labelView.text = displayName(entry)
    val pkgView = row.findViewById<TextView>(R.id.appPkg)
    pkgView.text = entry.pkg

    val enabledToggle = row.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.enabledToggle)
    enabledToggle.isChecked = entry.enabled
    enabledToggle.setOnCheckedChangeListener { _, checked ->
        val list = getWhitelist()
        val e = list.firstOrNull { it.pkg == entry.pkg } ?: return@setOnCheckedChangeListener
        e.enabled = checked
        saveWhitelist(list)
        if (!checked) toast("已停用 ${displayName(e)}")
    }

    row.findViewById<android.view.View>(R.id.appArrow).setOnClickListener {
        showAppEditDialog(entry)
    }
    return row
}

internal fun LauncherActivity.showAppEditDialog(entry: AppEntry) {
    val editView = layoutInflater.inflate(R.layout.dialog_edit_app, null)
    val pkgInput = editView.findViewById<EditText>(R.id.dialogPkgInput)
    pkgInput.setText(entry.pkg)
    val enabledSwitch = editView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.dialogEnabledSwitch)
    enabledSwitch.isChecked = entry.enabled
    val labelInput = editView.findViewById<EditText>(R.id.dialogLabelInput)
    labelInput.setText(entry.label)

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle("编辑应用")
        .setView(editView)
        .setPositiveButton("保存") { _, _ ->
            val newPkg = pkgInput.text?.toString()?.trim() ?: ""
            if (!isValidPackageName(newPkg)) {
                toast("包名无效")
                return@setPositiveButton
            }
            val list = getWhitelist()
            val e = list.firstOrNull { it.pkg == entry.pkg } ?: return@setPositiveButton
            if (newPkg != entry.pkg && list.any { it.pkg == newPkg }) {
                toast("该包名已存在")
                return@setPositiveButton
            }
            e.pkg = newPkg
            e.enabled = enabledSwitch.isChecked
            e.label = labelInput.text?.toString()?.trim() ?: ""
            saveWhitelist(list)
            refreshWhitelistUi()
        }
        .setNegativeButton("取消", null)
        .create()

    editView.findViewById<TextView>(R.id.dialogDelete).setOnClickListener {
        val list = getWhitelist()
        list.removeAll { it.pkg == entry.pkg }
        saveWhitelist(list)
        dialog.dismiss()
        refreshWhitelistUi()
    }
    dialog.show()
}

internal fun LauncherActivity.showAppPickerDialog() {
    val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
    val search = view.findViewById<EditText>(R.id.appPickerSearch)
    val listView = view.findViewById<ListView>(R.id.appPickerList)

    val added = getWhitelist().map { it.pkg }.toMutableSet()
    val installed = packageManager.getInstalledApplications(0)
        .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
        .mapNotNull { ai ->
            try {
                PickerItem(ai.packageName, packageManager.getApplicationLabel(ai).toString())
            } catch (_: Throwable) { null }
        }.sortedBy { it.name.lowercase() }

    val adapter = AppPickerAdapter(this, installed, added)
    listView.adapter = adapter
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            adapter.filter(s?.toString() ?: "")
        }
    })

    MaterialAlertDialogBuilder(this)
        .setTitle("选择应用")
        .setView(view)
        .setPositiveButton("完成", null)
        .show()
}
