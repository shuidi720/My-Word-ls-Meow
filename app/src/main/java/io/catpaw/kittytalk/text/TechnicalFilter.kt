package io.catpaw.kittytalk.text

/**
 * 技术文本过滤器：识别日志/命令输出/文件路径/链接/粘贴式大段文本。
 * 原逻辑从 PhraseProcessor 拆出，行为语义不变：
 *  - 技术行（时间戳行、Shizuku/cmd/appops/exit 等命令关键字、路径/链接）原样保留
 *  - 整段技术文本判定需至少命中 3 个特征，避免误伤正常聊天
 *  - 多行混合段（路径/链接 + 新输入文字）走分句逐行豁免，不做整段判定
 */
class TechnicalFilter {

    companion object {
        private val TIMESTAMP = Regex("""\[\d{2}:\d{2}:\d{2}\]""")
        private val URL = Regex("""https?://\S+""")
        private val EXT = Regex("""\.[a-zA-Z0-9]{1,5}$""")
    }

    /** 单行技术文本判定：日志/命令输出行的特征（时间戳、命令关键字、路径/链接） */
    fun isTechnicalLine(line: String): Boolean {
        if (line.isBlank()) return false
        return TIMESTAMP.containsMatchIn(line)
                || line.contains("Shizuku") || line.contains("deviceidle") || line.contains("standby-bucket")
                || line.contains("RUN_ANY_IN_BACKGROUND") || line.contains("RUN_IN_BACKGROUND")
                || line.contains("START_FOREGROUND") || line.contains("appops") || line.contains("cmd ")
                || line.contains("-> exit") || line.contains("exit=") || line.startsWith("am ")
                || line.contains("待机桶") || line.contains("Doze 白名单") || line.contains("Default mode") || line.contains("后台运行")
                || isFilePath(line)
    }

    /** 文件路径/URI/纯链接判定：仅判定单行内容（多行混合段交给分句逐行豁免） */
    fun isFilePath(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.contains('\n')) return false
        return t.startsWith("/storage/emulated/") || t.startsWith("/sdcard/") || t.startsWith("/data/")
                || t.startsWith("file://") || t.startsWith("content://") || t.startsWith("android.resource://")
                || URL.matches(t)
                || EXT.containsMatchIn(t) && (t.contains("/") || t.contains("\\"))
    }

    /**
     * 整段技术文本识别：至少命中 3 个特征才判定（避免误伤正常聊天文本）。
     * 多行混合段（日志/代码 + 用户在后面新打的字）：只看最后一个非空行——
     * 最后一行是用户输入（非技术行）时不整段跳过，让用户输入能正常触发变语。
     */
    fun isTechnicalText(s: String): Boolean {
        if (s.isEmpty()) return false
        val lines = s.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        // 多行混合段：最后一个非空行不是技术行 → 用户在技术文本后面打字，不整段跳过
        if (lines.size > 1 && !isTechnicalLine(lines.last())) return false
        var hits = 0
        for (line in lines) {
            if (isTechnicalLine(line)) hits++
            if (hits >= 3) return true
        }
        return hits >= 3
    }

    /** 粘贴识别：判断本次新增片段是否"粘贴式"（一次性插入大段文本），语义与原实现一致 */
    fun isPasteLikeSegment(s: String, pastePunct: String): Boolean {
        if (s.isEmpty()) return false
        if (s.all { it.isDigit() }) return false
        if (s.all { it == '\n' || it in pastePunct }) return false
        val hasPunct = s.any { it == '\n' || it in pastePunct }
        val hasDigit = s.any { it.isDigit() }
        if (hasPunct) return s.any { it.isLetter() }
        if (hasDigit) return false
        val special = s.any {
            !it.isLetter() && !it.isDigit() && it != '\n' && it !in pastePunct && !it.isWhitespace()
        }
        if (special) return false
        return s.length >= 30
    }
}
