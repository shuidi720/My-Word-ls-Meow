package io.catpaw.kittytalk.text

import io.catpaw.kittytalk.Rule

/**
 * 编译后的规则集：把规则列表预编译成查找结构，运行期 O(1) 整句查表 + 保序子串替换。
 *  - 整句匹配：HashSet 查重（替代线性 equals 遍历）
 *  - 子串替换：保留"按规则顺序依次应用"的语义（后规则可作用于前规则的结果），
 *    但替换动作改用 StringBuilder 手写扫描（替代 String.replace）
 *  - 排除规则语义与 PhraseProcessor 原行为一致
 */
class CompiledRuleSet(rules: List<Rule>) {

    /** 整句精确匹配表：from -> rule（trim 后查表） */
    private val wholeTable: Map<String, Rule> =
        rules.asSequence().filter { it.enabled && it.whole }.associateBy { it.from }

    /** 整句触发词集合（O(1) 查重） */
    private val wholeTriggers: Set<String> = wholeTable.keys

    /** 子串规则：保持 activeRules 原有顺序（LinkedHashMap 语义），供依次应用；编译期过滤空 from */
    private val substringChain: List<Rule> = rules.filter { it.enabled && !it.whole && it.from.isNotEmpty() }

    /** 是否有任意子串规则（快速空判断） */
    val hasSubstrings: Boolean = substringChain.isNotEmpty()

    /** 整句精确匹配：命中返回该规则，未命中返回 null */
    fun wholeMatch(text: String): Rule? = wholeTable[text.trim()]

    /** 整句触发词集合是否包含（供外部预判） */
    fun isWholeTrigger(text: String): Boolean = text.trim() in wholeTriggers

    /**
     * 依次应用所有子串规则（与旧实现语义一致）：
     * @return 替换后文本 与 排除命中信息（排除命中且落在句尾 = true）
     */
    fun replaceSubstrings(text: String, skipTailExclude: Boolean): Pair<String, Boolean> {
        var current = text
        for (rule in substringChain) {
            val from = rule.from
            // 快速剪枝：规则比文本长 / 文本不含规则首字符 → 跳过完整 contains 扫描（规则多时收益明显）
            if (from.length > current.length || current.indexOf(from[0]) < 0) continue
            if (!current.contains(from)) continue
            // 排除规则不再有"句尾不替换/不加后缀"的特殊行为，统一按普通子串替换处理；
            // 句尾附加叠加由 stripOverlap 去重（逻辑②），不再靠排除规则硬抑制（逻辑①已移除）。
            current = scanReplace(current, from, rule.randomTo())
        }
        // 第二个返回值始终为 false：整句匹配在 applyRulesNoSuffix 上层单独返回 true，
        // 子串替换（含排除规则）不再影响 res.second。
        return current to false
    }

    /** 子串规则是否命中（与旧 substringRuleMatch 语义一致：排除规则且落在句尾 → 不命中，等打标点再处理） */
    fun anySubstringHit(text: String): Boolean {
        for (rule in substringChain) {
            val from = rule.from
            if (from.length > text.length || text.indexOf(from[0]) < 0) continue
            if (!text.contains(from)) continue
            // 排除规则不再有"句尾不命中"的特殊处理，统一按普通子串命中处理
            return true
        }
        return false
    }

    /**
     * StringBuilder 手写扫描替换：替换 from 在 src 中的全部出现（等价 String.replace）。
     * 替换后去重：若替换结果 to 的末尾与替换位置后面紧跟的文本前缀有重叠，则跳过重叠部分。
     * 例：规则"好的"→"好喵"，文本"好的喵"→ 替换后"好喵"+"喵"重叠"喵"→ 结果"好喵"（而非"好喵喵"）。
     */
    private fun scanReplace(src: String, from: String, to: String): String {
        if (from.isEmpty()) return src
        if (from.length > src.length) return src
        val sb = StringBuilder(src.length + to.length)
        var i = 0
        while (i < src.length) {
            val idx = src.indexOf(from, i)
            if (idx < 0) {
                sb.append(src, i, src.length)
                break
            }
            sb.append(src, i, idx)
            // 替换后去重：to 末尾与后续文本前缀的最大重叠长度
            val afterStart = idx + from.length
            val after = if (afterStart < src.length) src.substring(afterStart) else ""
            val overlap = maxOverlapSuffixPrefix(to, after)
            sb.append(to)
            // 跳过后续文本中与 to 末尾重叠的部分
            i = afterStart + overlap
        }
        return sb.toString()
    }

    /** 计算 a 末尾与 b 前缀的最大重叠长度（0 表示无重叠） */
    private fun maxOverlapSuffixPrefix(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val maxLen = minOf(a.length, b.length)
        for (len in maxLen downTo 1) {
            if (a.endsWith(b.substring(0, len))) return len
        }
        return 0
    }
}
