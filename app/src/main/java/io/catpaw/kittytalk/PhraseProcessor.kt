package io.catpaw.kittytalk

import android.content.Context
import io.catpaw.kittytalk.text.CompiledRuleSet
import io.catpaw.kittytalk.text.PunctuationMapper
import io.catpaw.kittytalk.text.SentenceSplitter
import io.catpaw.kittytalk.text.SuffixPicker
import io.catpaw.kittytalk.text.TechnicalFilter

/**
 * 变语处理引擎（结构重写版）：无障碍服务与悬浮窗共用同一套触发/替换逻辑。
 *  - 每次 reload() 从 SettingsRepository 与 qq_settings 重新读取全部配置
 *  - 规则匹配走编译规则集（Hash 查表 + 手写扫描替换），后缀走加权轮询
 *  - 分句/标点/技术文本判定分别委托 SentenceSplitter / PunctuationMapper / TechnicalFilter
 */
object PhraseProcessor {

    // ---------- 对外配置状态（reload 后生效） ----------
    const val DEFAULT_PUNCT_BLOCK_CHARS = PunctuationMapper.DEFAULT_PUNCT_BLOCK_CHARS
    const val DEFAULT_SENTENCE_BLOCK_CHARS = " "
    const val KEEP_MARKS = PunctuationMapper.KEEP_MARKS
    /** 句尾附加时保留在正文原位（后缀之前）、不挪到后缀之后的语气符号：省略号、全角/半角波浪号 */
    const val TAIL_STAY_MARKS = "…～~"

    var activeRules: List<Rule> = emptyList()
    var activeSuffixes: List<Suffix> = emptyList()
    var punctuationTrigger = false
    var punctEatPeriod = false
    var sentenceTrigger = false
    /** 纯标点判定符号集：整段仅由这些符号组成不触发句尾附加 */
    var punctBlockChars = DEFAULT_PUNCT_BLOCK_CHARS
    /** 分句触发屏蔽符号：分句拆句/粘贴识别中视为普通字符（不触发） */
    var sentenceBlockChars = DEFAULT_SENTENCE_BLOCK_CHARS

    // ---------- 内部编译/选择结构 ----------
    private var compiled: CompiledRuleSet = CompiledRuleSet(emptyList())
    private var picker: SuffixPicker = SuffixPicker(emptyList())
    private val punct = PunctuationMapper(eatPeriod = { punctEatPeriod }, blockChars = { punctBlockChars })
    private val tech = TechnicalFilter()
    private val splitter = SentenceSplitter(
        suffixes = { activeSuffixes.map { it.text } },
        pickSuffix = { picker.next() },
        isTechnical = { line -> tech.isTechnicalLine(line) }
    )

    /** 从 SettingsRepository 与 qq_settings 重新加载配置并重编译规则集 */
    fun reload(context: Context) {
        val cfg = SettingsRepository.load(context)
        val active = cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()
        activeRules = active?.rules ?: emptyList()
        activeSuffixes = (active?.suffixes ?: emptyList()).filter { it.enabled && it.text.isNotBlank() }
        compiled = CompiledRuleSet(activeRules)
        picker = SuffixPicker(activeSuffixes)
        val prefs = context.getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
        punctuationTrigger = prefs.getBoolean("punctuation_trigger", false)
        punctEatPeriod = prefs.getBoolean("punct_eat_period", false)
        sentenceTrigger = prefs.getBoolean("sentence_trigger", false)
        // 空格作为内置屏蔽始终生效（不占用界面输入框，避免不可见空格让用户误以为没填）；
        // 用户在框内填入的是"额外"屏蔽符号，二者合并去重
        val userBlock = prefs.getString("sentence_block_chars", null).orEmpty()
        sentenceBlockChars = (DEFAULT_SENTENCE_BLOCK_CHARS + userBlock).toSet().joinToString("")
        splitter.updateBlockChars(sentenceBlockChars)
    }

    /** 从当前分类随机选一条句尾附加文字（加权轮询） */
    fun pickSuffix(): String = picker.next()

    /** 剥掉文本末尾一个已附加的句尾后缀（按后缀长度从长到短匹配），无匹配返回原文本 */
    fun stripSuffix(text: String): String {
        for (s in activeSuffixes.map { it.text }.sortedByDescending { it.length }) {
            if (s.isNotEmpty() && text.endsWith(s)) return text.removeSuffix(s)
        }
        return text
    }

    // 把含替换结果和尾巴的当前文本还原为接近用户原始输入
    fun restoreUserInput(text: String): String {
        var content = text
        // 触发标点（句号/换行）先摘除，后缀可能在它们之前
        var tailTrigger = ""
        while (content.isNotEmpty() && (content.last() == '。' || content.last() == '\n')) {
            tailTrigger = content.last() + tailTrigger
            content = content.dropLast(1)
        }
        // 先直接尝试剥掉句末后缀（后缀本身可能以标点结尾，如"喵~"；"喵+表情"作为整体也是一个普通后缀）
        // 单字后缀（如"喵"）不剥：无法区分用户原文与附加后缀，去重靠 stripOverlap（避免"喵喵喵"被误剥成"喵喵"）
        for (s in activeSuffixes.map { it.text }.sortedByDescending { it.length }) {
            if (s.isNotEmpty() && content.endsWith(s)) {
                val sBody = s.dropLastWhile { it in KEEP_MARKS }
                if (sBody.length >= 2) {
                    content = content.removeSuffix(s)
                    break
                }
            }
        }
        // 再摘除句末保留标点（！？…～），后缀可能在它们之前（如"喵~！"）；
        // 后缀本身也可能以保留标点结尾（如"喵~"），需把后缀拆成"正文+标点尾巴"来匹配
        var tailMarks = ""
        while (content.isNotEmpty() && content.last() in KEEP_MARKS) {
            tailMarks = content.last() + tailMarks
            content = content.dropLast(1)
        }
        for (s in activeSuffixes.map { it.text }.sortedByDescending { it.length }) {
            if (s.isEmpty()) continue
            var sBody = s
            var sMarks = ""
            while (sBody.isNotEmpty() && sBody.last() in KEEP_MARKS) {
                sMarks = sBody.last() + sMarks
                sBody = sBody.dropLast(1)
            }
            // 单字后缀正文（如"喵"）默认不剥：无法区分用户原文与附加后缀，去重靠 stripOverlap
            if (sBody.isNotEmpty() && content.endsWith(sBody)) {
                // 但当末尾有用户新打的保留标点 tailMarks，且它正好是后缀自带标点尾巴 sMarks 的前缀时，
                // 说明末尾这个单字正文就是旧后缀残留（后缀本身以标点结尾，如"喵！"）：
                // 否则用户再打相同标点时旧后缀剥不掉，会重新 pickSuffix 附加一次，叠成双后缀。
                // 误剥也无害：后面会重新走一次规则替换+后缀，结果一致。
                val safeSingle = sBody.length == 1 && tailMarks.isNotEmpty() && tailMarks.startsWith(sMarks)
                if (sBody.length >= 2 || safeSingle) {
                    // 正常情况：tailMarks 以 sMarks 开头；
                    // 删后缀末尾标点：tailMarks 为空（用户删掉了后缀末尾的标点，如"喵呜～"→"喵呜"），也剥掉正文
                    if (tailMarks.startsWith(sMarks) || tailMarks.isEmpty()) {
                        content = content.removeSuffix(sBody)
                        if (tailMarks.isNotEmpty()) tailMarks = tailMarks.removePrefix(sMarks)
                        break
                    }
                }
            }
        }
        // 反向替换最近一次的替换结果，尽量还原（子串规则 + 整句规则都参与还原）。
        // 整句规则还原：如果 content 精确等于某个整句规则的 to（或 to+保留标点已被摘除），还原成 from。
        // 不还原会导致：整句"你好"→"你好喵"后打逗号，restoreUserInput 还原成"你好喵，"，
        // applyRulesNoSuffix("你好喵") 不匹配整句（不是"你好"），又加句尾后缀导致叠加（bug3）。
        for (rule in activeRules) {
            if (!rule.enabled) continue
            if (rule.whole) {
                // 整句规则：精确匹配 to 才还原（整句是精确匹配语义，不做子串 replace）
                for (c in rule.to.split(Rule.SEP)) {
                    if (c.isNotEmpty() && content == c) {
                        content = rule.from
                        break
                    }
                }
            } else {
                // 子串规则：全局 replace 还原
                for (c in rule.to.split(Rule.SEP)) {
                    if (c.isNotEmpty()) content = content.replace(c, rule.from)
                }
            }
        }
        return content + tailMarks + tailTrigger
    }

    /** 文本是否以句尾标点结尾（标点触发模式以此判断何时执行替换） */
    fun endsWithSentencePunct(text: String): Boolean = punct.endsWithSentencePunct(text)

    /** 标点触发模式下是否应处理当前文本：以句尾标点或换行符结尾 */
    fun shouldTriggerPunct(text: String): Boolean = punct.shouldTrigger(text)

    /** 标点触发模式下的文本转换结果 */
    class PunctTransformResult(
        val body: String,
        val trailing: String,
        val trailingNewline: Boolean,
        val hasTrigger: Boolean
    )

    /** 标点触发转换（语义见 PunctuationMapper.transform） */
    fun transformPunctuation(raw: String, context: String? = null): PunctTransformResult {
        val r = punct.transform(raw, context)
        return PunctTransformResult(r.body, r.trailing, r.trailingNewline, r.hasTrigger)
    }

    /** 单行技术文本判定：日志/命令输出的行特征 */
    fun isTechnicalLine(line: String): Boolean = tech.isTechnicalLine(line)

    /** 文件路径/URI/纯链接判定：仅判定单行内容 */
    fun isFilePath(s: String): Boolean = tech.isFilePath(s)

    /** 分句附加：按标点/换行拆子句，对含文字的子句加句尾文字，技术行原样保留 */
    fun applySentenceSuffix(body: String): String = splitter.applySuffix(body)

    /** 循环剥掉一段文本末尾所有已附加的句尾后缀（含累积的多个） */
    fun stripSuffixAll(part: String): String = splitter.stripAll(part)

    /** 整句幂等还原：按分句分隔符拆子句，剥掉每个子句末尾已附加的尾巴 */
    fun restoreSentenceSuffixes(text: String): String = splitter.restoreAll(text)

    /** 句尾附加去重：text 末尾与 suffix 前缀重叠则去掉重叠部分（供 doProcess 调用，与 transformFloat 保持一致） */
    fun stripOverlap(text: String, suffix: String): String = splitter.stripOverlap(text, suffix)

    /** 计算两个字符串的最长公共前缀长度 */
    private fun commonPrefixLength(a: String, b: String): Int {
        val maxLen = minOf(a.length, b.length)
        var i = 0
        while (i < maxLen && a[i] == b[i]) i++
        return i
    }

    /** 整段技术文本识别：至少命中 3 个特征才判定（避免误伤正常聊天文本） */
    fun isTechnicalText(s: String): Boolean = tech.isTechnicalText(s)

    /** 标点触发模式下，文本是否命中任意一条启用的子串替换规则——子串独立刷新用 */
    fun substringRuleMatch(text: String): Boolean = compiled.anySubstringHit(text)

    /** 整句触发词是否命中（供外部预判：整句替换独立于标点触发） */
    fun isWholeTrigger(text: String): Boolean = compiled.isWholeTrigger(text)

    /**
     * 规则应用（不附加句尾）：
     *  - 整句精确匹配（基于原始消息）：命中即整句返回随机候选（不再子串替换，也不再加后缀）
     *  - 子串替换：所有匹配的规则全部应用；排除规则同样执行替换，仅影响句尾附加是否抑制
     * @return 替换后文本 与 是否排除命中且落在句尾
     */
    fun applyRulesNoSuffix(text: String, skipTailExclude: Boolean = false): Pair<String, Boolean> {
        val whole = compiled.wholeMatch(text)
        if (whole != null) return whole.randomTo() to true
        return compiled.replaceSubstrings(text, skipTailExclude)
    }

    /** 粘贴识别：判断本次新增片段是否"粘贴式"（一次性插入大段文本） */
    fun isPasteLikeSegment(s: String): Boolean = tech.isPasteLikeSegment(s, pastePunctChars())

    /** 粘贴识别的标点集：基础标点剔除分句屏蔽符号 */
    fun pastePunctChars(): String = punct.pastePunctChars(sentenceBlockChars)

    /** 判断字符串是否全部由（用户允许的）标点符号组成：空串返回 false */
    fun isPunctOnly(s: String): Boolean = punct.isPunctOnly(s)

    /**
     * 悬浮窗输入实时处理：与文本框增量触发逻辑完全一致——
     * 上次写入(lastSet) + 新输入 → 只处理新增片段（规则替换+句尾附加），不再重复转换已有内容；
     * 非增量（删除/中间修改）→ 还原用户原文后整句处理。
     * @return 替换后的完整文本与光标位置（文本与 raw 相同表示无需变更，光标位置无效）
     */
    /** 多行文本中变化行的检测结果 */
    private data class LineDiff(
        val workRaw: String,
        val workLastSet: String,
        val linePrefix: String,
        val lineSuffix: String,
        val lineOffset: Int,
        /** 删除整行时直接返回原文，不做任何处理 */
        val directReturn: Boolean = false
    )

    /**
     * 检测多行文本中唯一变化的行，返回该行及上下文；返回 null 表示不进入单行模式。
     *  - 行数相同且只有一行变化 → 处理该行
     *  - 比上次多一行 → 处理新增行
     *  - 比上次少一行 → directReturn=true（纯删除行，不附加后缀）
     *  - 兜底：标点触发开启时，找到第一个以句尾标点结尾的行
     */
    private fun detectChangedLine(raw: String, lastSet: String, punctuationTrigger: Boolean): LineDiff? {
        if (!raw.contains("\n")) return null
        val rawLines = raw.split("\n")
        val lastLines = lastSet.split("\n")
        var changeIdx = -1
        var changeLastLine = ""
        if (rawLines.size == lastLines.size) {
            for (i in rawLines.indices) {
                if (rawLines[i] != lastLines[i]) {
                    changeIdx = if (changeIdx >= 0) -2 else i
                }
            }
            if (changeIdx >= 0) changeLastLine = lastLines[changeIdx]
        } else if (rawLines.size == lastLines.size + 1) {
            for (i in rawLines.indices) {
                val reduced = rawLines.take(i) + rawLines.drop(i + 1)
                if (reduced == lastLines) {
                    changeIdx = i
                    changeLastLine = ""
                    break
                }
            }
        } else if (rawLines.size == lastLines.size - 1) {
            // 删除一行：纯缩短操作，只同步状态不附加后缀
            for (i in lastLines.indices) {
                val reduced = lastLines.take(i) + lastLines.drop(i + 1)
                if (reduced == rawLines) {
                    return LineDiff("", "", "", "", 0, directReturn = true)
                }
            }
        }
        if (changeIdx >= 0) {
            // 非变化行从 lastSet 取，确保后缀不被输入法修改导致丢失
            val prefixList = lastLines.take(changeIdx.coerceAtMost(lastLines.size))
            val linePrefix = if (prefixList.isNotEmpty()) prefixList.joinToString("\n") + "\n" else ""
            val suffixDrop = if (rawLines.size == lastLines.size) changeIdx + 1 else changeIdx
            val suffixList = lastLines.drop(suffixDrop.coerceAtMost(lastLines.size))
            val lineSuffix = if (suffixList.isNotEmpty()) "\n" + suffixList.joinToString("\n") else ""
            return LineDiff(rawLines[changeIdx], changeLastLine, linePrefix, lineSuffix, linePrefix.length)
        }
        // 兜底：标点触发开启且 lastSet 非空时，找到第一个以句尾标点结尾的行
        if (punctuationTrigger && lastSet.isNotEmpty()) {
            val punctIdx = rawLines.indexOfFirst { line -> line.isNotEmpty() && shouldTriggerPunct(line) }
            if (punctIdx >= 0) {
                val prefixList = rawLines.take(punctIdx)
                val linePrefix = if (prefixList.isNotEmpty()) prefixList.joinToString("\n") + "\n" else ""
                val suffixList = rawLines.drop(punctIdx + 1)
                val lineSuffix = if (suffixList.isNotEmpty()) "\n" + suffixList.joinToString("\n") else ""
                return LineDiff(rawLines[punctIdx], "", linePrefix, lineSuffix, linePrefix.length)
            }
        }
        return null
    }

    fun transformFloat(raw: String, lastSet: String, isDeleting: Boolean): Pair<String, Int> {
        if (raw.isEmpty()) return raw to 0
        // 幂等判断：文本与上次写入完全一致时不处理（避免 setText 触发的回显事件被重复处理，
        // 导致"附加后缀→剥掉后缀→再附加"的死循环，bug：多行句号触发自动删句尾附加）
        if (raw == lastSet) return raw to raw.length

        // === 每行独立变化：多行文本中只有一行变化时，只处理那一行 ===
        val lineDiff = detectChangedLine(raw, lastSet, punctuationTrigger)
        if (lineDiff?.directReturn == true) return raw to raw.length
        val singleLineMode = lineDiff != null
        val workRaw = lineDiff?.workRaw ?: raw
        val workLastSet = lineDiff?.workLastSet ?: lastSet
        val linePrefix = lineDiff?.linePrefix ?: ""
        val lineSuffix = lineDiff?.lineSuffix ?: ""
        val lineOffset = lineDiff?.lineOffset ?: 0
        fun mergeLine(line: String): String = linePrefix + line + lineSuffix

        // 增量还原：单行模式下强制整行处理（变化行的 lastSet 是原始内容，增量路径会漏附加）
        val incremental = !singleLineMode && workLastSet.isNotEmpty() && workRaw.startsWith(workLastSet)
        var delta = if (incremental) workRaw.removePrefix(workLastSet) else restoreUserInput(workRaw)
        if (delta.isEmpty()) return raw to 0
        // 技术文本识别：粘贴/输入的日志、命令输出不触发替换附加，原样保留
        if (!isDeleting && isTechnicalText(delta)) return raw to 0
        // 粘贴识别：一次性插入大段文本 → 临时关闭标点触发，让分句触发接管。
        // 单行模式下（包括兜底触发）强制 pasteLike=false：单行模式说明是用户逐行打字，
        // 不是粘贴；多行文本含换行会被 isPasteLikeSegment 误判为粘贴，导致 pt=null 句号不消费。
        // 真正的粘贴（单行模式检测失败，delta 是完整多行文本）才走 pasteLike=true。
        val pasteLike = !singleLineMode && !isDeleting && isPasteLikeSegment(delta)
        // 标点触发模式：单行模式下只看变化行本身 + 变化行是最后一行且后面有换行
        // （不能用 mergeLine 检测：变化行在链接前面时，合并文本末尾是链接，检测不到标点）
        val substringHit = !isDeleting && substringRuleMatch(delta)
        // 整句命中预判：整句替换应独立于标点触发，即使没有标点也应生效（bug4）。
        // 基于 restoreUserInput(workRaw) 预判：整句匹配是精确匹配用户原始输入。
        val wholeHit = !isDeleting && compiled.isWholeTrigger(restoreUserInput(workRaw))
        val punctHit = if (singleLineMode) {
            shouldTriggerPunct(workRaw) ||
                (lineSuffix.startsWith("\n") && lineSuffix.removePrefix("\n").isBlank())
        } else {
            shouldTriggerPunct(workRaw)
        }
        // 标点触发模式下，只有既没有标点、也没有子串命中、也没有整句命中时才跳过。
        // 之前没有 wholeHit 判断，导致整句替换在无标点时被提前跳过（bug4）。
        if (punctuationTrigger && !pasteLike && !isDeleting && !punctHit && !substringHit && !wholeHit) return raw to 0
        // 标点触发且本次新增是触发标点，且上次写入还不是引擎产物时，整句还原处理
        var wholeRun = !incremental
        if (punctuationTrigger && !pasteLike && !substringHit && punctHit) {
            val lastHasSuffix = workLastSet.isNotEmpty() && activeSuffixes.any { s -> s.text.isNotEmpty() && workLastSet.endsWith(s.text) }
            val lastEndsMark = workLastSet.isNotEmpty() && workLastSet.last() in KEEP_MARKS
            if (!lastHasSuffix && !lastEndsMark) {
                delta = restoreUserInput(workRaw)
                wholeRun = true
            }
        }
        // 分句是否生效：只在粘贴时生效，正常打字时自动关闭分句（只走句尾附加），
        // 避免正常打字/打多标点时分句后缀被反复刷新；粘贴时一次性给所有分句加后缀
        val sentenceActive = sentenceTrigger && pasteLike
        // 标点转换：子串替换命中时也执行标点转换（句号消费），之前 !substringHit 导致子串命中时句号不消费（bug3）。
        // 只有分句触发+粘贴同时满足时才跳过标点转换（粘贴临时交给分句）。
        // 句号消费的 canEatPeriod 需要基于完整文本判断（含字母才消费），增量模式下 delta 可能是纯标点"。"，
        // 传入 workRaw 作为 context（修复：增量模式下句号不消费的问题）
        val pt = if (punctuationTrigger && !(sentenceTrigger && pasteLike)) transformPunctuation(delta, workRaw) else null
        var core = pt?.body ?: delta
        var trailingPunct = ""
        if (pt == null) {
            while (core.isNotEmpty() && core.last() in KEEP_MARKS) {
                // 省略号/波浪号（…～~）是语气延续符号，保留在正文原位（后缀之前），
                // 不随其他标点一起挪到句尾后缀之后（如“你好～”→“你好～喵”而非“你好喵～”）
                if (core.last() in TAIL_STAY_MARKS) break
                trailingPunct = core.last() + trailingPunct
                core = core.dropLast(1)
            }
        }
        val res = applyRulesNoSuffix(core, false)  // skipTailExclude 已废弃：排除规则变普通子串替换，叠加靠 stripOverlap 去重
        val replaced = res.first
        val punctOnly = isPunctOnly(delta)
        val tail = when {
            isDeleting -> ""
            punctOnly -> ""
            sentenceActive -> ""
            // 子串独立刷新：仅在标点触发模式下，子串命中但没打标点时不附加（打字时只替换不附加）；
            // 只开句尾附加（punctuationTrigger=false）时，子串/整句命中后应立即附加；
            // 打了标点（punctHit=true）时正常附加句尾后缀；
            // 整句匹配命中（res.second）也允许附加——叠加由 stripOverlap 全局去重（bug2：整句替换后标点触发不附加）
            substringHit && !punctHit && punctuationTrigger -> ""
            // 子串/整句替换结果本身已以句尾标点结尾时，不再附加句尾后缀：
            // 如整句"我靠"→"我喵了个咪？！"，替换内容自带结束标点，再附加会变成"我喵了个咪？！喵"。
            // 仅作用于非标点触发路径（pt==null），不影响标点后移机制；
            // 要求 replaced 与 core 末尾字符不同，以区分"替换带来的标点"与"用户自己输入的 …/～"
            // （后者保留原行为："你好～"→"你好～喵"）。
            pt == null && replaced != core && replaced.isNotEmpty() &&
                replaced.last() in KEEP_MARKS && core.lastOrNull() != replaced.last() -> ""
            else -> pickSuffix()
        }
        val base = if (incremental && !wholeRun) workLastSet else ""
        val coreFinal = if (sentenceActive && !isDeleting && replaced.isNotEmpty() && activeSuffixes.isNotEmpty()) {
            applySentenceSuffix(replaced)
        } else {
            replaced
        }
        var text = if (pt != null) {
            val attached = if (pt.hasTrigger && !punctOnly) tail else ""
            val core = if (attached.isNotEmpty()) splitter.stripOverlap(replaced, attached) else replaced
            base + core + attached + pt.trailing + (if (pt.trailingNewline) "\n" else "")
        } else {
            val core = if (tail.isNotEmpty()) splitter.stripOverlap(coreFinal, tail) else coreFinal
            base + core + tail + trailingPunct
        }
        // —— 纯句尾附加/分句模式（非标点触发、非删除）的光标与"顶开"逻辑 ——
        val cursorResult = computeCursor(
            text = text, workRaw = workRaw, workLastSet = workLastSet, tail = tail,
            sentenceActive = sentenceActive, wholeHit = res.second, incremental = incremental,
            delta = delta, punctOnly = punctOnly,
            punctuationTrigger = punctuationTrigger, isDeleting = isDeleting
        )
        text = cursorResult.text
        var cursor = cursorResult.cursor
        // 每行独立变化：合并回多行文本，光标加行偏移
        if (singleLineMode) {
            text = mergeLine(text)
            cursor += lineOffset
        }
        return text to cursor
    }

    /** 光标计算结果 */
    private data class CursorResult(val text: String, val cursor: Int)

    /**
     * 纯句尾附加/分句模式下的光标与"顶开"逻辑：
     *  - 检测到用户在已有后缀前插入文字/标点时，把插入内容放到后缀前，光标停在后缀前
     *  - …/～ 等语气延续符号保留在后缀前，其余触发标点后置到后缀之后
     *  - 分句模式下整句还原后光标停在最后一个后缀前
     */
    private fun computeCursor(
        text: String,
        workRaw: String,
        workLastSet: String,
        tail: String,
        sentenceActive: Boolean,
        wholeHit: Boolean,
        incremental: Boolean,
        delta: String,
        punctOnly: Boolean,
        punctuationTrigger: Boolean,
        isDeleting: Boolean
    ): CursorResult {
        var resultText = text
        var cursor = resultText.length
        if (punctuationTrigger || isDeleting || activeSuffixes.isEmpty()) {
            return CursorResult(resultText, cursor)
        }
        val lastTail = activeSuffixes.map { it.text }
            .filter { it.isNotEmpty() && workLastSet.endsWith(it) }
            .maxByOrNull { it.length }
        var corrected = false
        if (lastTail != null) {
            val lastUserPart = workLastSet.removeSuffix(lastTail)
            val rawTail = activeSuffixes.map { it.text }
                .filter { it.isNotEmpty() && workRaw.endsWith(it) }
                .maxByOrNull { it.length }
            if (rawTail != null && workRaw.removeSuffix(rawTail).startsWith(lastUserPart)) {
                val rawBase = workRaw.removeSuffix(rawTail)
                val inserted = rawBase.removePrefix(lastUserPart)
                if (inserted.isNotEmpty()) {
                    val res2 = applyRulesNoSuffix(inserted, false)
                    val punctInsert = inserted.all { it == '\n' || it in KEEP_MARKS }
                    val t = if (punctInsert) {
                        // …/～（TAIL_STAY_MARKS）保留在后缀前；其余触发标点后置到后缀之后
                        val stay = inserted.filter { it in TAIL_STAY_MARKS }
                        val moved = inserted.filter { it != '\n' && it !in TAIL_STAY_MARKS }
                        val nl = if (inserted.contains('\n')) "\n" else ""
                        lastUserPart + stay + rawTail + moved + nl
                    } else {
                        // 用户在已有后缀前插入文字，插入内容经规则替换后，
                        // 替换结果末尾与已有后缀开头可能重复（如整句结果"主人早上好喵"+已有后缀"喵呜～"），
                        // 必须先 stripOverlap 去重再拼后缀，否则叠成"主人早上好喵喵呜～"
                        lastUserPart + splitter.stripOverlap(res2.first, rawTail) + rawTail
                    }
                    resultText = t
                    val stayLen = if (punctInsert) inserted.count { it in TAIL_STAY_MARKS } else 0
                    cursor = when {
                        punctInsert && stayLen > 0 -> lastUserPart.length + stayLen
                        punctInsert -> t.length
                        else -> t.length - rawTail.length
                    }
                    corrected = true
                }
            } else if (incremental && !isPunctOnly(delta)) {
                val res2 = applyRulesNoSuffix(delta, false)
                // 同上前缀：替换结果末尾与已有后缀开头重复时先去重，避免叠字（如"喵喵"）
                val t = lastUserPart + splitter.stripOverlap(res2.first, lastTail) + lastTail
                corrected = true
                cursor = t.length - lastTail.length
                resultText = t
            }
        }
        if (!corrected && sentenceActive && !wholeHit && resultText.isNotEmpty()) {
            val userWhole = restoreSentenceSuffixes(workRaw)
            val r2 = applyRulesNoSuffix(userWhole, false)
            resultText = if (r2.second) r2.first else applySentenceSuffix(r2.first)
            val newTail = activeSuffixes.map { it.text }
                .filter { it.isNotEmpty() && resultText.endsWith(it) }
                .maxByOrNull { it.length }
            cursor = if (newTail != null) resultText.length - newTail.length else resultText.length
            corrected = true
        } else if (!corrected && !sentenceActive && tail.isNotEmpty() && resultText.endsWith(tail)) {
            cursor = resultText.length - tail.length
        }
        return CursorResult(resultText, cursor)
    }
}
