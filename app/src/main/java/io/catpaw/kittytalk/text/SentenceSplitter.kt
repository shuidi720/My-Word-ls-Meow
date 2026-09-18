package io.catpaw.kittytalk.text

/**
 * 分句器：负责按标点/换行把文本拆成子句，并对每个含文字的子句附加句尾文字；
 * 同时提供"幂等还原"（剥掉已附加的尾巴，防累积）。
 * 原逻辑从 PhraseProcessor 拆出，行为语义不变：
 *  - 分句基础分隔符：标点 + 换行（用户屏蔽的符号剔除后动态生成正则）
 *  - 纯标点段、纯符号段（颜文字/表情）、纯数字段不附加，原样保留
 *  - 技术行（日志/命令输出）原样保留，不追加句尾文字
 */
class SentenceSplitter(
    private val suffixes: () -> List<String>,
    private val pickSuffix: () -> String,
    private val isTechnical: (String) -> Boolean
) {

    companion object {
        private const val SENTENCE_BASE = "，,。！!？?…～~；;\n"
    }

    private var sentenceBlockChars: String = " "

    fun updateBlockChars(blocked: String) {
        sentenceBlockChars = blocked
    }

    /** 动态分句正则：基础分句标点剔除用户屏蔽符号；全被屏蔽时返回永不匹配 */
    private fun splitRegex(): Regex {
        val keep = SENTENCE_BASE.filter { it !in sentenceBlockChars }
        return if (keep.isEmpty()) Regex("(?!)") else Regex("([${Regex.escape(keep)}]+)")
    }

    /**
     * 去掉 text 末尾与 suffix 重叠的部分，避免附加后重复。
     * 匹配三种重叠：
     *  1) text 末尾 == suffix 前缀（如"你好喵"+"喵呜～"→"你好"+"喵呜～"）
     *  2) text 末尾 == suffix 去掉前 N 个字后的前缀（删掉了后缀的前 N 个字，
     *     如"你好呜"+"喵呜～"→"呜"是"喵呜～"去掉"喵"后的前缀→"你好"+"喵呜～"）
     *  3) text 末尾 == suffix 删掉中间任意一个字后的前缀（删掉了后缀中间字，
     *     如"你好13456"+"123456~"→"13456"是"123456~"删掉"2"后的前缀→"你好"+"123456~"）
     * 至少保留 suffix 的一个字符（不允许完全吞掉 suffix）。
     */
    fun stripOverlap(text: String, suffix: String): String {
        if (text.isEmpty() || suffix.isEmpty()) return text
        // 允许完全重叠：text=="喵", suffix=="喵" 时返回""（之前 maxOverlap=suffix.length-1=0 导致单字后缀完全不去重，bug1）
        val maxOverlap = minOf(text.length, suffix.length)
        for (len in maxOverlap downTo 1) {
            // 1) text 末尾 == suffix 前缀
            if (text.endsWith(suffix.substring(0, len))) {
                return text.substring(0, text.length - len)
            }
            // 2) text 末尾 == suffix.substring(n) 的前缀（删掉了后缀前 n 个字）
            for (n in 1..suffix.length - len) {
                if (text.endsWith(suffix.substring(n, n + len))) {
                    return text.substring(0, text.length - len)
                }
            }
            // 3) text 末尾 == suffix 删掉中间第 i 个字后的前缀（删掉了后缀中间字）
            for (i in 1 until suffix.length - 1) {
                val removed = suffix.substring(0, i) + suffix.substring(i + 1)
                if (len <= removed.length && text.endsWith(removed.substring(0, len))) {
                    return text.substring(0, text.length - len)
                }
            }
        }
        return text
    }

    /**
     * 去掉 sep 开头与 suffix 结尾重叠的部分，避免分句分隔符与后缀末尾标点重复。
     * 例：sep="～", suffix="喵呜～" → suffix 结尾"～"与 sep 开头"～"重叠 → 返回""（吃掉重复的～）
     * 例：sep="，", suffix="喵呜～" → 不重叠 → 返回"，"
     * 至少保留 suffix 的一个字符（不允许完全吞掉 suffix）。
     */
    fun stripSuffixOverlap(sep: String, suffix: String): String {
        if (sep.isEmpty() || suffix.isEmpty()) return sep
        val maxOverlap = minOf(sep.length, suffix.length - 1)
        for (len in maxOverlap downTo 1) {
            if (sep.startsWith(suffix.substring(suffix.length - len))) {
                return sep.substring(len)
            }
        }
        return sep
    }

    /**
     * 分句附加：把文本按标点/换行分成子句，每个子句末尾加一次附加文字，标点原样保留；
     * 只对"含文字（字母/汉字）"的子句附加——纯标点段、纯符号段（颜文字/表情）、纯数字段不附加。
     *
     * 已有后缀保护：整句重跑时，已有后缀的分句保留原后缀不重新随机，
     * 只有没有后缀的新分句才随机附加——避免打多标点时所有分句后缀被"刷新"。
     * 检测方式：合并 part + 后续分隔符，去掉末尾分隔符后检查是否匹配某个后缀。
     */
    fun applySuffix(body: String): String {
        if (body.isBlank()) return body
        val sepSet = SENTENCE_BASE.toSet()
        val suffixList = suffixes().sortedByDescending { it.length }

        fun hasExistingSuffix(text: String): Boolean {
            // 后缀可能以分隔符结尾（如"喵～""喵！"），text 末尾可能有用户后续打的额外分隔符；
            // 不能一次性去掉所有分隔符（会把后缀中的分隔符也去掉导致检测失败），
            // 改为逐步去掉末尾分隔符，每一步都检查是否以某个后缀结尾，最多去掉5个分隔符。
            fun endsWithSuffix(t: String): Boolean {
                for (s in suffixList) {
                    if (s.isNotEmpty() && t.endsWith(s)) return true
                }
                return false
            }
            var t = text
            var removed = 0
            while (t.isNotEmpty() && t.last() in sepSet && removed < 5) {
                if (endsWithSuffix(t)) return true
                t = t.dropLast(1)
                removed++
            }
            return endsWithSuffix(t)
        }

        val sb = StringBuilder()
        var i = 0
        for (m in splitRegex().findAll(body)) {
            val part = body.substring(i, m.range.first)
            val sep = m.value
            if (isTechnical(part) || part.isEmpty() || !part.any { it.isLetter() }) {
                sb.append(part)
                sb.append(sep)
                i = m.range.last + 1
                continue
            }
            // 检查分句末尾是否已有后缀（合并part+sep，去掉末尾分隔符后检测）
            if (hasExistingSuffix(part + sep)) {
                // 已有后缀，保留原文本不重新随机
                sb.append(part)
                sb.append(sep)
            } else {
                // 没有后缀，随机附加
                val suffix = pickSuffix()
                sb.append(stripOverlap(part, suffix))
                sb.append(suffix)
                sb.append(stripSuffixOverlap(sep, suffix))
            }
            i = m.range.last + 1
        }
        if (i < body.length) {
            val part = body.substring(i)
            if (isTechnical(part) || part.isEmpty() || !part.any { it.isLetter() }) {
                sb.append(part)
            } else if (hasExistingSuffix(part)) {
                sb.append(part)
            } else {
                val suffix = pickSuffix()
                sb.append(stripOverlap(part, suffix))
                sb.append(suffix)
            }
        }
        return sb.toString()
    }

    /**
     * 只对最后一个分句附加后缀（增量模式下使用，避免重新随机之前分句的后缀）。
     * 找到最后一个分句分隔符，只给分隔符后的最后一段文字附加后缀；分隔符及之前的内容原样保留。
     * 如果以分隔符结尾（如"你好，"），则给分隔符前的最后一段文字附加后缀，分隔符保留在末尾。
     * 如果没有分隔符（整段只有一个分句），效果等同于 applySuffix。
     */
    fun applySuffixToLast(body: String): String {
        if (body.isBlank()) return body
        val sepSet = SENTENCE_BASE.toSet()
        // 先剥掉末尾所有分隔符，处理后再加回去（如"你好，"→work="你好", trailingSep="，"）
        var trailingSep = ""
        var work = body
        while (work.isNotEmpty() && work.last() in sepSet) {
            trailingSep = work.last().toString() + trailingSep
            work = work.dropLast(1)
        }
        if (work.isEmpty()) return body  // 全是分隔符，不处理
        // 找到 work 中最后一个分隔符
        var lastSepIdx = -1
        for (i in work.indices) {
            if (work[i] in sepSet) lastSepIdx = i
        }
        val lastPart = if (lastSepIdx >= 0) work.substring(lastSepIdx + 1) else work
        val before = if (lastSepIdx >= 0) work.substring(0, lastSepIdx + 1) else ""
        if (lastPart.isEmpty() || !lastPart.any { it.isLetter() } || isTechnical(lastPart)) {
            return body  // 最后一段没有文字或纯技术文本，不附加
        }
        val suffix = pickSuffix()
        val stripped = stripOverlap(lastPart, suffix)
        // 分隔符与后缀末尾的重叠去重
        val sepChar = if (lastSepIdx >= 0) work[lastSepIdx].toString() else ""
        val sepStripped = if (sepChar.isNotEmpty()) stripSuffixOverlap(sepChar, suffix) else ""
        val beforeFixed = if (sepChar.isNotEmpty()) before.removeSuffix(sepChar) + sepStripped else ""
        // 末尾分隔符与后缀末尾的重叠去重
        val trailingStripped = if (trailingSep.isNotEmpty()) stripSuffixOverlap(trailingSep, suffix) else trailingSep
        return beforeFixed + stripped + suffix + trailingStripped
    }

    /**
     * 剥掉一段文本末尾已附加的句尾后缀。
     * 注意：单字后缀（如"喵"）与用户原文无法区分（用户可能打"喵喵喵"），因此单字后缀不剥——
     * 去重完全靠 stripOverlap（附加前去掉正文末尾与后缀前缀的重叠）。
     * 只有长度≥2的后缀才剥（如"喵呜～""喵！"），因为多字后缀与用户原文重叠的概率较低。
     *
     * 后缀可能以分句分隔符结尾（如"喵呜～""喵！"）。分句拆分时该分隔符被拆进
     * 后续 match，导致本子句末尾只剩"喵呜"/"喵"。因此剥离前先剥掉末尾所有分隔符，
     * 匹配到"后缀文本部分"时从分隔符尾巴里扣掉后缀含的分隔符数量，剩余分隔符保留。
     */
    fun stripAll(part: String): String {
        val sepSet = SENTENCE_BASE.toSet()
        // 先剥掉末尾所有分隔符，得到纯文本 q 和分隔符尾巴 tail
        var tail = ""
        var q = part
        while (q.isNotEmpty() && q.last() in sepSet) {
            tail = q.last() + tail
            q = q.dropLast(1)
        }
        for (s in suffixes().sortedByDescending { it.length }) {
            if (s.isEmpty()) continue
            // 单字后缀不剥（无法区分用户原文与附加后缀，去重靠 stripOverlap）
            val sText = if (s.last() in sepSet) s.dropLastWhile { it in sepSet } else s
            if (sText.length < 2) continue
            // 完整匹配：q 末尾就是完整后缀
            if (q.endsWith(s)) {
                return q.removeSuffix(s) + tail
            }
            // 后缀以分隔符结尾：q 末尾可能是后缀的文本部分（分隔符被拆到 tail 里了）
            if (s.last() in sepSet) {
                val sSep = s.substring(sText.length)
                val sSepLen = sSep.length
                if (sText.isNotEmpty() && q.endsWith(sText) && tail.length >= sSepLen && tail.substring(0, sSepLen) == sSep) {
                    return q.removeSuffix(sText) + tail.substring(sSepLen)
                }
            }
        }
        return part
    }

    /**
     * 整句幂等还原：把整句按分句分隔符拆成子句，每个子句末尾剥掉已附加的句尾后缀（可累积多个），
     * 分隔符原样保留。用于分句触发模式的"幂等分句"——无论上次状态是否同步，重新附加都不会累积重复尾巴。
     *
     * 注意：后缀可能以分句分隔符结尾（如"喵呜～""喵！"），分句时该分隔符被拆进 match，
     * 导致子句末尾只剩"喵呜"/"喵"。因此剥离前需把子句与后续分隔符合并，让 stripAll 看到完整后缀。
     */
    fun restoreAll(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder()
        var i = 0
        for (m in splitRegex().findAll(text)) {
            val part = text.substring(i, m.range.first)
            // 合并子句+后续分隔符再剥离：让跨在分隔符上的后缀（如"喵呜～"的～）能被完整匹配
            sb.append(stripAll(part + m.value))
            i = m.range.last + 1
        }
        if (i < text.length) sb.append(stripAll(text.substring(i)))
        return sb.toString()
    }
}
