package io.catpaw.kittytalk.text

/**
 * 标点映射器：负责句尾标点/换行的触发识别、句号消费、保留标点后置、纯标点判定。
 * 原逻辑从 PhraseProcessor 拆出，行为语义不变：
 *  - 句末保留标点："。，！？!?…～~" 均触发并保留，移到句尾附加文字之后
 *  - 句号消费开关：开启后句末句号"。"仅作为触发标识不保留（仅对"含文字且不含数字"的文本生效）
 *  - 换行符作为行分隔符保留在句末
 */
class PunctuationMapper(
    private val eatPeriod: () -> Boolean,
    private val blockChars: () -> String
) {

    companion object {
        /** 句末保留标点：标点触发模式下保留并移到句末（后缀之后） */
        const val KEEP_MARKS = "。！？!?…～~，"
        /** 默认已屏蔽符号：整段只含这些标点时不触发句尾附加（纯标点不触发） */
        const val DEFAULT_PUNCT_BLOCK_CHARS = "。，！？!?…～~、；：·—–—《》〈〉【】「」『』（）()[]{}<>,.;:\""
        /** 粘贴识别的标点集基础（剔除分句屏蔽符号后生效） */
        private const val PASTE_BASE = "，,。！!？?…～~；;、：:·—–—《》〈〉【】「」『』（）()[]{}<>\"'"
    }

    /** 标点触发转换结果 */
    class TransformResult(
        val body: String,
        val trailing: String,
        val trailingNewline: Boolean,
        val hasTrigger: Boolean
    )

    /** 判断字符串是否全部由（用户允许的）标点符号组成：空串返回 false */
    fun isPunctOnly(s: String): Boolean {
        if (s.isEmpty()) return false
        for (ch in s) if (ch !in blockChars()) return false
        return true
    }

    /** 文本是否以句尾标点结尾（标点触发模式以此判断何时执行替换） */
    fun endsWithSentencePunct(text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty()) return false
        return t.last() in KEEP_MARKS
    }

    /** 标点触发模式下是否应处理当前文本：以句尾标点或换行符结尾 */
    fun shouldTrigger(text: String): Boolean {
        if (text.endsWith("\n")) return true
        if (endsWithSentencePunct(text)) return true
        return false
    }

    /**
     * 标点触发转换：
     *  - 句末保留标点触发并保留，移到句尾附加文字之后
     *  - 句号消费：开启后句号仅作触发标识（限"含文字且不含数字"的文本）
     *  - 换行符作为行分隔符保留在句末
     */
    fun transform(raw: String, context: String? = null): TransformResult {
        var t = raw
        var trailing = ""
        var eatenPeriod = false
        // canEatPeriod 基于 context（完整文本）判断：增量模式下 delta 可能是纯标点"。"，
        // 此时用完整文本判断是否含字母（修复：增量模式下句号不消费的问题）
        val contextForEat = context ?: raw
        val canEatPeriod = contextForEat.any { it.isLetter() } && contextForEat.none { it.isDigit() }
        // 多行文本：先对每一行末尾的句号做消费（只消费句号，其他标点保留到原逻辑处理）。
        // 原逻辑只剥文本末尾的 KEEP_MARKS，中间行末尾的句号不会被消费，导致多行句号消失失灵。
        if (t.contains("\n") && eatPeriod() && canEatPeriod) {
            val lines = t.split("\n")
            var anyEaten = false
            val processed = lines.map { line ->
                if (line.endsWith("。")) {
                    anyEaten = true
                    line.dropLast(1)
                } else line
            }
            if (anyEaten) {
                t = processed.joinToString("\n")
                eatenPeriod = true
            }
        }
        while (t.isNotEmpty() && t.last() in KEEP_MARKS) {
            val ch = t.last()
            if (ch == '。' && eatPeriod() && canEatPeriod) {
                eatenPeriod = true
                t = t.dropLast(1)
                continue
            }
            trailing = ch + trailing
            t = t.dropLast(1)
        }
        var newline = false
        if (t.isNotEmpty() && t.last() == '\n') {
            newline = true
            t = t.dropLast(1)
        }
        val hasTrigger = trailing.isNotEmpty() || newline || eatenPeriod || t.any { it == '。' || it == '\n' }
        return TransformResult(t, trailing, newline, hasTrigger)
    }

    /** 剥掉文本末尾的句尾标点（用于非标点触发路径的临时处理），返回 (正文, 标点串) */
    fun stripTrailingMarks(text: String): Pair<String, String> {
        var body = text
        var marks = ""
        while (body.isNotEmpty() && body.last() in KEEP_MARKS) {
            marks = body.last() + marks
            body = body.dropLast(1)
        }
        return body to marks
    }

    /** 粘贴识别的标点集：基础标点剔除分句屏蔽符号（字+屏蔽符号不再因该符号识别为粘贴触发） */
    fun pastePunctChars(blocked: String): String = PASTE_BASE.filter { it !in blocked }
}
