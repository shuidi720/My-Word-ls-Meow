package io.catpaw.kittytalk

import android.view.LayoutInflater
import android.widget.EditText
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.catpaw.kittytalk.databinding.DialogAddRuleBinding
import io.catpaw.kittytalk.databinding.ItemRuleBinding
import io.catpaw.kittytalk.databinding.ItemSuffixBinding

/**
 * 替换规则 Tab：分类管理、子串/整句规则、句尾附加、导入导出
 * 注意：maxVisible / suffixesExpanded / rulesExpanded / wholeRulesExpanded / exportTarget / cfg
 * 字段留在 LauncherActivity 中
 */

internal fun LauncherActivity.setupCategoryControls() {
    binding.tabRules.addCategoryButton.setOnClickListener {
        showNewCategoryDialog()
    }
}

internal fun LauncherActivity.rebuildCategoryChips() {
    val group = binding.tabRules.categoryChips
    group.removeAllViews()
    for ((i, cat) in cfg.categories.withIndex()) {
        val chip = Chip(this).apply {
            text = cat.name
            isCheckable = true
            isChecked = cat.active
            setOnClickListener { activateCategory(i) }
            setOnLongClickListener {
                showCategoryActions(i)
                true
            }
        }
        group.addView(chip)
    }
}

internal fun LauncherActivity.activateCategory(index: Int) {
    if (index < 0 || index >= cfg.categories.size) return
    cfg.categories.forEachIndexed { i, c -> c.active = i == index }
    applyConfig()
}

internal fun LauncherActivity.showNewCategoryDialog() {
    val input = EditText(this).apply {
        hint = "分类名称"
        setSingleLine(true)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("新建分类")
        .setView(input)
        .setPositiveButton("创建") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                toast("分类名称不能为空")
            } else {
                cfg.categories.add(Category(name))
                applyConfig()
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

internal fun LauncherActivity.showCategoryActions(index: Int) {
    val cat = cfg.categories.getOrNull(index) ?: return
    MaterialAlertDialogBuilder(this)
        .setTitle("分类：${cat.name}")
        .setItems(arrayOf("重命名", "删除")) { _, which ->
            when (which) {
                0 -> showRenameCategoryDialog(index)
                1 -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除分类")
                        .setMessage("确定删除分类「${cat.name}」及其所有规则吗？")
                        .setPositiveButton("删除") { _, _ ->
                            cfg.categories.removeAt(index)
                            if (cfg.categories.isNotEmpty() && cfg.categories.none { it.active }) {
                                cfg.categories[0].active = true
                            }
                            applyConfig()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        .show()
}

internal fun LauncherActivity.showRenameCategoryDialog(index: Int) {
    val cat = cfg.categories.getOrNull(index) ?: return
    val input = EditText(this).apply {
        setText(cat.name)
        setSelection(text.length)
        setSingleLine(true)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("重命名分类")
        .setView(input)
        .setPositiveButton("确定") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                cat.name = name
                applyConfig()
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

internal fun LauncherActivity.activeCategory(): Category? =
    cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()

// ---------- 列表（默认最多显示 3 条，超出可展开） ----------

internal fun LauncherActivity.setupListToggles() {
    binding.tabRules.suffixToggle.setOnClickListener {
        suffixesExpanded = !suffixesExpanded
        refreshRulesUi()
    }
    binding.tabRules.rulesToggle.setOnClickListener {
        rulesExpanded = !rulesExpanded
        refreshRulesUi()
    }
    binding.tabRules.wholeRulesToggle.setOnClickListener {
        wholeRulesExpanded = !wholeRulesExpanded
        refreshRulesUi()
    }
    binding.tabRules.toggleAllRulesButton.setOnClickListener {
        val rules = activeCategory()?.rules?.filter { !it.whole } ?: return@setOnClickListener
        val anyOn = rules.any { it.enabled }
        rules.forEach { it.enabled = !anyOn }
        applyConfig()
    }
    binding.tabRules.toggleAllWholeRulesButton.setOnClickListener {
        val rules = activeCategory()?.rules?.filter { it.whole } ?: return@setOnClickListener
        val anyOn = rules.any { it.enabled }
        rules.forEach { it.enabled = !anyOn }
        applyConfig()
    }
}

internal fun LauncherActivity.updateBatchButtonText() {
    val rules = activeCategory()?.rules ?: emptyList()
    binding.tabRules.toggleAllRulesButton.text =
        if (rules.any { !it.whole && it.enabled }) "全部关闭规则" else "全部启用规则"
    binding.tabRules.toggleAllWholeRulesButton.text =
        if (rules.any { it.whole && it.enabled }) "全部关闭整句" else "全部启用整句"
}

internal fun LauncherActivity.renderSuffixList() {
    val container = binding.tabRules.suffixListContainer
    container.removeAllViews()
    val suffixes = activeCategory()?.suffixes ?: emptyList()
    val toShow = if (suffixesExpanded) suffixes else suffixes.take(maxVisible)

    for (suffix in toShow) {
        val b = ItemSuffixBinding.inflate(LayoutInflater.from(this), container, false)
        b.sfxText.text = suffix.text
        b.sfxSwitch.isChecked = suffix.enabled
        b.sfxSwitch.setOnCheckedChangeListener { _, checked ->
            suffix.enabled = checked
            applyConfig()
        }
        b.root.setOnClickListener {
            val input = EditText(this)
            input.setText(suffix.text)
            input.setSelection(input.text?.length ?: 0)
            MaterialAlertDialogBuilder(this)
                .setTitle("编辑附加文字")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val t = input.text?.toString()?.trim().orEmpty()
                    if (t.isEmpty()) {
                        toast("附加文字不能为空")
                    } else {
                        val list = activeCategory()?.suffixes
                        val idx = list?.indexOf(suffix) ?: -1
                        if (list != null && idx >= 0) {
                            list[idx] = suffix.copy(text = t)
                        }
                        applyConfig()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        b.root.setOnLongClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除附加文字")
                .setMessage("确定删除「${suffix.text}」吗？")
                .setPositiveButton("删除") { _, _ ->
                    activeCategory()?.suffixes?.remove(suffix)
                    applyConfig()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        container.addView(b.root)
    }

    val toggle = binding.tabRules.suffixToggle
    if (suffixes.size > maxVisible) {
        toggle.text = if (suffixesExpanded) "收起附加文字" else "显示全部附加文字（${suffixes.size}）"
        toggle.visibility = android.view.View.VISIBLE
    } else {
        toggle.visibility = android.view.View.GONE
    }
}

internal fun LauncherActivity.renderRulesList() {
    val container = binding.tabRules.rulesListContainer
    container.removeAllViews()
    val rules = activeCategory()?.rules?.filter { !it.whole } ?: emptyList()
    val toShow = if (rulesExpanded) rules else rules.take(maxVisible)

    for (rule in toShow) {
        val b = ItemRuleBinding.inflate(LayoutInflater.from(this), container, false)
        b.ruleFrom.text = rule.from
        b.ruleTo.text = rule.preview
        b.ruleSwitch.isChecked = rule.enabled
        b.ruleSwitch.setOnCheckedChangeListener { _, checked ->
            rule.enabled = checked
            applyConfig()
        }
        b.root.setOnLongClickListener {
            showEditRuleDialog(rule)
            true
        }
        container.addView(b.root)
    }

    val toggle = binding.tabRules.rulesToggle
    if (rules.size > maxVisible) {
        toggle.text = if (rulesExpanded) "收起规则" else "显示全部规则（${rules.size}）"
        toggle.visibility = android.view.View.VISIBLE
    } else {
        toggle.visibility = android.view.View.GONE
    }
}

internal fun LauncherActivity.renderWholeRulesList() {
    val container = binding.tabRules.wholeRulesListContainer
    container.removeAllViews()
    val rules = activeCategory()?.rules?.filter { it.whole } ?: emptyList()
    val toShow = if (wholeRulesExpanded) rules else rules.take(maxVisible)

    for (rule in toShow) {
        val b = ItemRuleBinding.inflate(LayoutInflater.from(this), container, false)
        b.ruleFrom.text = rule.from
        b.ruleTo.text = rule.preview
        b.ruleSwitch.isChecked = rule.enabled
        b.ruleSwitch.setOnCheckedChangeListener { _, checked ->
            rule.enabled = checked
            applyConfig()
        }
        b.root.setOnLongClickListener {
            showEditRuleDialog(rule)
            true
        }
        container.addView(b.root)
    }

    binding.tabRules.emptyWholeHint.visibility =
        if (rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

    val toggle = binding.tabRules.wholeRulesToggle
    if (rules.size > maxVisible) {
        toggle.text = if (wholeRulesExpanded) "收起整句响应" else "显示全部整句响应（${rules.size}）"
        toggle.visibility = android.view.View.VISIBLE
    } else {
        toggle.visibility = android.view.View.GONE
    }
}

internal fun LauncherActivity.refreshRulesUi() {
    rebuildCategoryChips()
    renderSuffixList()
    renderRulesList()
    renderWholeRulesList()
    updateBatchButtonText()
    val cat = activeCategory()
    val replaceCount = cat?.rules?.count { !it.whole } ?: 0
    binding.tabRules.currentCategoryName.text =
        if (cat != null) "当前：${cat.name}（$replaceCount 条规则）" else ""
    binding.tabRules.emptyRulesHint.visibility =
        if (cat == null || replaceCount == 0) android.view.View.VISIBLE else android.view.View.GONE
}

// ---------- 后缀输入 ----------

internal fun LauncherActivity.setupSuffixControls() {
    binding.tabRules.addSuffixButton.setOnClickListener {
        val cat = activeCategory()
        val text = binding.tabRules.newSuffixInput.text?.toString()?.trim()
        if (cat == null) {
            toast("请先创建一个分类")
        } else if (text.isNullOrEmpty()) {
            toast("附加文字不能为空")
        } else {
            cat.suffixes.add(Suffix(text, true))
            binding.tabRules.newSuffixInput.setText("")
            applyConfig()
        }
    }
}

internal fun LauncherActivity.setupFab() {
    binding.tabRules.addRuleFab.setOnClickListener {
        val cat = activeCategory()
        if (cat == null) {
            toast("请先创建一个分类")
        } else {
            showAddRuleDialog()
        }
    }
}

internal fun LauncherActivity.showAddRuleDialog() {
    val dialogBinding = DialogAddRuleBinding.inflate(layoutInflater)
    dialogBinding.ruleModeGroup.check(R.id.modeReplace)
    dialogBinding.ruleModeGroup.addOnButtonCheckedListener { _, _, _ ->
        applyModeStyle(dialogBinding)
    }
    applyModeStyle(dialogBinding)
    MaterialAlertDialogBuilder(this)
        .setTitle("添加规则")
        .setView(dialogBinding.root)
        .setPositiveButton("添加") { _, _ ->
            val from = dialogBinding.fromInput.text.toString().trim()
            val to = dialogBinding.toInput.text.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(Rule.SEP)
            if (from.isEmpty() || to.isEmpty()) {
                toast("原文本与替换为不能为空")
            } else {
                val whole = dialogBinding.ruleModeGroup.checkedButtonId == R.id.modeWhole
                val cat = activeCategory()
                val exist = cat?.rules?.firstOrNull { it.from == from }
                if (exist != null) {
                    mergeRuleCandidates(exist, to)
                    applyConfig()
                    toast("检测到“$from”已有规则，候选已自动并入，未新增")
                } else {
                    cat?.rules?.add(Rule(from, to, true, whole, false))
                    applyConfig()
                }
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

internal fun LauncherActivity.mergeRuleCandidates(target: Rule, newTo: String) {
    val merged = target.to.split(Rule.SEP)
        .filter { it.isNotEmpty() }
        .toMutableList()
    for (c in newTo.split(Rule.SEP).filter { it.isNotEmpty() }) {
        if (c !in merged) merged.add(c)
    }
    target.to = merged.joinToString(Rule.SEP)
}

internal fun LauncherActivity.showEditRuleDialog(rule: Rule) {
    val db = DialogAddRuleBinding.inflate(layoutInflater)
    db.ruleModeGroup.check(if (rule.whole) R.id.modeWhole else R.id.modeReplace)
    db.ruleModeGroup.addOnButtonCheckedListener { _, _, _ -> applyModeStyle(db) }
    applyModeStyle(db)
    db.fromInput.setText(rule.from)
    db.toInput.setText(rule.to.split(Rule.SEP).joinToString("\n"))
    MaterialAlertDialogBuilder(this)
        .setTitle("编辑规则")
        .setView(db.root)
        .setPositiveButton("保存") { _, _ ->
            val from = db.fromInput.text.toString().trim()
            val to = db.toInput.text.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(Rule.SEP)
            if (from.isEmpty() || to.isEmpty()) {
                toast("原文本与替换为不能为空")
            } else {
                val cat = activeCategory()
                val conflict = cat?.rules?.firstOrNull { it !== rule && it.from == from }
                if (conflict != null) {
                    mergeRuleCandidates(conflict, to)
                    cat.rules.remove(rule)
                    applyConfig()
                    toast("检测到“$from”已有规则，候选已并入，原规则已移除")
                } else {
                    rule.from = from
                    rule.to = to
                    rule.whole = db.ruleModeGroup.checkedButtonId == R.id.modeWhole
                    applyConfig()
                }
            }
        }
        .setNeutralButton("删除") { _, _ ->
            MaterialAlertDialogBuilder(this)
                .setTitle("删除规则")
                .setMessage("确定删除「${rule.from} → ${rule.preview}」？")
                .setPositiveButton("删除") { _, _ ->
                    activeCategory()?.rules?.remove(rule)
                    applyConfig()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        .setNegativeButton("取消", null)
        .show()
}

internal fun LauncherActivity.themeColor(attr: Int): Int {
    val tv = android.util.TypedValue()
    return if (theme.resolveAttribute(attr, tv, true)) tv.data else 0
}

internal fun LauncherActivity.applyModeStyle(db: DialogAddRuleBinding) {
    val sel = db.ruleModeGroup.checkedButtonId
    styleModeButton(db.modeReplace, sel == R.id.modeReplace)
    styleModeButton(db.modeWhole, sel == R.id.modeWhole)
}

internal fun LauncherActivity.styleModeButton(b: com.google.android.material.button.MaterialButton, on: Boolean) {
    if (on) {
        b.backgroundTintList =
            android.content.res.ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorPrimary))
        b.setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimary))
    } else {
        b.backgroundTintList =
            android.content.res.ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorSurfaceVariant))
        b.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
}

internal fun LauncherActivity.addNhyPresetFromEasterEgg() {
    if (cfg.categories.none { it.name == "NHYzhuang" }) {
        cfg.categories.add(SettingsRepository.nhyPreset())
        applyConfig()
        toast("已添加 nhy 预设，可在规则页选择使用")
    } else {
        toast("nhy 预设已存在")
    }
}

internal fun LauncherActivity.showExportSelectionDialog() {
    val cats = cfg.categories
    if (cats.isEmpty()) {
        toast("没有可导出的分类")
        return
    }
    val names = cats.map { it.name }.toTypedArray()
    val checked = cats.map { true }.toBooleanArray()
    MaterialAlertDialogBuilder(this)
        .setTitle("选择要导出的分类")
        .setMultiChoiceItems(names, checked) { _, i, isChecked -> checked[i] = isChecked }
        .setPositiveButton("导出") { _, _ ->
            val selected = cats.filterIndexed { i, _ -> checked[i] }
            if (selected.isEmpty()) {
                toast("未选择任何分类")
                return@setPositiveButton
            }
            exportTarget = selected
            exportLauncher.launch("qqnhy_rules.json")
        }
        .setNegativeButton("取消", null)
        .show()
}

// ---------- 导入 ----------

internal fun LauncherActivity.mergeImport(imported: SettingsRepository.Config) {
    val localNames = cfg.categories.map { it.name }.toSet()
    val newCats = imported.categories.filter { it.name !in localNames }
    cfg.categories.addAll(newCats.map { deepCopy(it) })
    val dupNames = imported.categories.filter { it.name in localNames }.map { it.name }.distinct()
    if (dupNames.isEmpty()) {
        applyConfig()
        toast("已导入 ${newCats.size} 个新分类")
    } else {
        showDuplicateChoice(newCats.size, dupNames, imported)
    }
}

internal fun LauncherActivity.deepCopy(c: Category): Category = Category(
    c.name,
    c.rules.map { Rule(it.from, it.to, it.enabled, it.whole, it.exclude) }.toMutableList(),
    c.active,
    c.suffixes.map { Suffix(it.text, it.enabled) }.toMutableList()
)

internal fun LauncherActivity.showDuplicateChoice(newCount: Int, dupNames: List<String>, imported: SettingsRepository.Config) {
    val names = dupNames.joinToString("、")
    val dlg = MaterialAlertDialogBuilder(this)
        .setTitle("发现同名分类")
        .setMessage("以下分类已存在：$names\n\n当前新增了 $newCount 个分类。同名分类如何处理？")
        .setPositiveButton("保留本地") { _, _ ->
            toast("已导入 $newCount 个新分类，保留本地同名分类")
            applyConfig()
        }
        .setNegativeButton("用导入替换") { _, _ ->
            for (c in imported.categories) {
                val idx = cfg.categories.indexOfFirst { it.name == c.name }
                if (idx >= 0) cfg.categories[idx] = deepCopy(c)
            }
            toast("同名分类已替换为导入内容")
            applyConfig()
        }
        .setNeutralButton("两个都保留") { _, _ ->
            for (c in imported.categories) {
                if (cfg.categories.any { it.name == c.name }) {
                    var i = 1
                    var newName = c.name
                    while (cfg.categories.any { it.name == newName }) {
                        newName = "${c.name}_$i"
                        i++
                    }
                    cfg.categories.add(deepCopy(c).apply { name = newName })
                }
            }
            toast("已导入全部分类（同名已改名保留）")
            applyConfig()
        }
        .setCancelable(false)
        .show()
}
