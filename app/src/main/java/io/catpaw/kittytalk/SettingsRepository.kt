package io.catpaw.kittytalk

import android.content.Context
import io.catpaw.kittytalk.data.AllowlistEntity
import io.catpaw.kittytalk.data.CategoryEntity
import io.catpaw.kittytalk.data.KittyDatabase
import io.catpaw.kittytalk.data.RuleEntity
import io.catpaw.kittytalk.data.SuffixEntity
import org.json.JSONArray
import org.json.JSONObject

/** 单条替换规则 */
data class Rule(
    var from: String,
    var to: String,
    var enabled: Boolean = true,
    /** true 表示"整句精确匹配"：整条消息等于 from 时，把整句替换成 to */
    var whole: Boolean = false,
    /** 排除开关：命中该规则时仅输出本规则响应，不再应用其他替换、也不触发句尾附加（每个规则独立） */
    var exclude: Boolean = false
) {
    companion object {
        /** to 用换行分隔多个候选，命中后随机取其一（复刻脚本的随机语录） */
        const val SEP = "\n"
    }

    /** 若 to 含多个候选则随机取一个，否则原样返回 */
    fun randomTo(): String {
        val parts = to.split(SEP)
        return if (parts.size > 1) parts[java.util.Random().nextInt(parts.size)] else to
    }

    /** 列表展示：最多显示 5 条候选，其余在点开规则后可看到 */
    val preview: String get() {
        val all = to.split(SEP).filter { it.isNotEmpty() }
        val show = all.take(5)
        val s = show.joinToString("、")
        return if (all.size > 5) "$s…(+${all.size - 5})" else s
    }
}

/** 一条句尾附加文字，可独立开关是否参与随机 */
data class Suffix(val text: String, var enabled: Boolean = true)

/** 一个规则分类，同一时刻只启用一个分类；后缀归入分类（多条，随机选一条） */
data class Category(
    var name: String,
    val rules: MutableList<Rule> = mutableListOf(),
    var active: Boolean = false,
    val suffixes: MutableList<Suffix> = mutableListOf()
)

/** 白名单里的一个应用条目 */
data class AppEntry(
    var pkg: String,
    var enabled: Boolean = true,
    var clipboard: Boolean = false,
    var label: String = ""
)

/**
 * 规则/分类/后缀的统一存取与导入导出（3.2 结构重写版）。
 * 存储模型：SQLite（Room）——category_table / phrase_table / tail_table；
 * 旧 SharedPreferences（qq_reply_rules 的 config_json / 旧扁平 key）在首次加载时一次性迁移进库。
 * 后缀属于分类，同一条文本发送时从该分类的多条后缀中加权轮询选一条。
 */
object SettingsRepository {

    private const val PREFS_NAME = "qq_reply_rules"
    private const val KEY_CONFIG = "config_json"
    // 旧数据 key（用于一次性迁移）
    private const val LEGACY_KEY_RULES = "rules_json"
    private const val LEGACY_KEY_SUFFIX = "suffix_text"
    // 2.1 默认配置迁移标记（一次性）
    private const val KEY_V21_MIGRATED = "v21_migrated"
    // 2.2 表情后缀预置标记（一次性）
    private const val KEY_V22_MIGRATED = "v22_migrated"
    // 3.2 迁移到 SQLite 标记（一次性）
    private const val KEY_DB_MIGRATED = "db_migrated"

    /** 预置的"喵+表情"句尾附加文字（默认关闭，用户勾选即启用；也可自行添加，如只写表情则触发后不带"喵"） */
    val PRESET_EMOTICON_SUFFIXES = listOf(
        "喵ฅ^•ﻌ•^ฅ",
        "喵ฅ^••^ฅ",
        "喵ฅ•̀∀•́ฅ",
        "喵ฅ^._.^ฅ",
        "喵(=^･ω･^=)",
        "喵(^ω^ฅ)",
        "喵( Φ ω Φ )",
        "喵≡ω≡"
    )

    const val DEFAULT_SUFFIX = "喵～"

    data class Config(val categories: MutableList<Category>)

    /** 从 SQLite 读取；库为空时尝试从旧 SharedPreferences 一次性迁移，都没有则初始化默认配置 */
    fun load(context: Context): Config {
        val dao = KittyDatabase.get(context).phrases()
        val rows = dao.categories()
        if (rows.isNotEmpty()) {
            val cats = rows.map { row -> hydrateCategory(dao, row) }.toMutableList()
            // 从数据库加载后也确保预设分类存在（用户旧数据可能缺少"更喵的娘"/"猫娘"预设）
            return Config(cats.ensureCatgirlPreset().ensureMiao2Preset())
        }
        // 库空：走旧数据迁移 / 默认初始化
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacySuffix = prefs.getString(LEGACY_KEY_SUFFIX, DEFAULT_SUFFIX) ?: DEFAULT_SUFFIX
        val migrated: MutableList<Category>
        val json = prefs.getString(KEY_CONFIG, null)
        migrated = if (json != null) {
            try {
                parseConfigJson(json, legacySuffix, prefs)
            } catch (_: Exception) {
                defaultCategories(legacySuffix)
            }
        } else {
            val legacyRulesJson = prefs.getString(LEGACY_KEY_RULES, null)
            if (legacyRulesJson != null) {
                try {
                    val arr = JSONArray(legacyRulesJson)
                    val rules = mutableListOf<Rule>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        rules.add(Rule(o.getString("from"), o.getString("to"), o.getBoolean("enabled")))
                    }
                    if (rules.isEmpty()) defaultCategories(legacySuffix)
                    else mutableListOf(Category("默认", rules, true, mutableListOf(Suffix(legacySuffix, true))))
                } catch (_: Exception) {
                    defaultCategories(legacySuffix)
                }
            } else {
                defaultCategories(legacySuffix)
            }
        }
        save(context, Config(migrated))
        prefs.edit().putBoolean(KEY_DB_MIGRATED, true).apply()
        return Config(migrated)
    }

    /** 写库：事务内全量重写分类树 */
    fun save(context: Context, cfg: Config) {
        val dao = KittyDatabase.get(context).phrases()
        val categories = mutableListOf<CategoryEntity>()
        val rules = mutableListOf<RuleEntity>()
        val suffixes = mutableListOf<SuffixEntity>()
        var cid = 1L
        for (c in cfg.categories) {
            categories.add(CategoryEntity(cid, c.name, if (c.active) 1 else 0))
            c.rules.forEachIndexed { idx, r ->
                rules.add(RuleEntity(0, cid, r.from, r.to, if (r.enabled) 1 else 0, if (r.whole) 1 else 0, if (r.exclude) 1 else 0, idx))
            }
            c.suffixes.forEachIndexed { idx, s ->
                suffixes.add(SuffixEntity(0, cid, s.text, if (s.enabled) 1 else 0, idx))
            }
            cid++
        }
        dao.replaceCategories(categories, rules, suffixes)
    }

    /** 从实体行还原分类对象 */
    private fun hydrateCategory(dao: io.catpaw.kittytalk.data.PhraseDao, row: CategoryEntity): Category {
        val rules = dao.rulesOf(row.cid).map { r ->
            Rule(r.trigger, r.replacement, r.active == 1, r.whole == 1, r.exclude == 1)
        }.toMutableList()
        val suffixes = dao.suffixesOf(row.cid).map { s -> Suffix(s.content, s.active == 1) }.toMutableList()
        return Category(row.name, rules, row.active == 1, suffixes)
    }

    /** 解析 config_json（旧 SP 迁移路径），含旧版全局后缀迁移与 2.1/2.2 预设链 */
    private fun parseConfigJson(json: String, legacySuffix: String, prefs: android.content.SharedPreferences): MutableList<Category> {
        val obj = JSONObject(json)
        val cats = mutableListOf<Category>()
        val catsJson = obj.optJSONArray("categories")
        if (catsJson != null) {
            for (i in 0 until catsJson.length()) {
                val cj = catsJson.getJSONObject(i)
                val rulesArr = cj.optJSONArray("rules")
                val rules = mutableListOf<Rule>()
                if (rulesArr != null) {
                    for (j in 0 until rulesArr.length()) {
                        val rj = rulesArr.getJSONObject(j)
                        rules.add(
                            Rule(
                                rj.optString("from"),
                                migrateTo(rj.optString("to")),
                                rj.optBoolean("enabled", true),
                                rj.optBoolean("whole", false),
                                rj.optBoolean("exclude", false)
                            )
                        )
                    }
                }
                val sfxArr = cj.optJSONArray("suffixes")
                val suffixes = mutableListOf<Suffix>()
                if (sfxArr != null) {
                    for (j in 0 until sfxArr.length()) {
                        val item = sfxArr.opt(j)
                        when (item) {
                            is JSONObject -> {
                                val s = item.optString("text")
                                if (s.isNotBlank()) suffixes.add(Suffix(s, item.optBoolean("enabled", true)))
                            }
                            else -> {
                                val s = sfxArr.optString(j)
                                if (s.isNotBlank()) suffixes.add(Suffix(s, true))
                            }
                        }
                    }
                }
                cats.add(
                    Category(
                        cj.optString("name", "未命名"),
                        rules,
                        cj.optBoolean("active", false),
                        suffixes
                    )
                )
            }
        }
        if (cats.isEmpty()) return defaultCategories(legacySuffix).ensureCatgirlPreset().ensureMiao2Preset()

        // 旧版全局后缀迁移：若活动分类没有后缀，则把旧全局后缀放进去
        val oldGlobal = obj.optString("suffix", "").ifBlank { legacySuffix }
        if (oldGlobal.isNotBlank()) {
            val active = cats.firstOrNull { it.active } ?: cats.first()
            if (active.suffixes.isEmpty()) active.suffixes.add(Suffix(oldGlobal, true))
        }
        return cats.ensureOnlyOneActive().ensureCatgirlPreset().ensureMiao2Preset().migrateToV21Defaults(prefs).migrateToV22Suffixes(prefs)
    }

    private fun migrateTo(v: String): String = v.replace("||", "\n")

    /**
     * 2.1 默认配置迁移（仅首次加载执行一次）：
     *  - 所有分类的规则全部置为关闭（原本的分类规则默认关闭）
     *  - 句末附加只启用 "喵～"、"喵！"、"喵呜～"，其余后缀关闭；缺失的补上
     */
    private fun MutableList<Category>.migrateToV21Defaults(prefs: android.content.SharedPreferences): MutableList<Category> {
        if (prefs.getBoolean(KEY_V21_MIGRATED, false)) return this
        val keep = setOf("喵～", "喵！", "喵呜～")
        for (c in this) {
            for (r in c.rules) r.enabled = false
            val have = c.suffixes.map { it.text }.toMutableSet()
            for (s in c.suffixes) s.enabled = s.text in keep
            for (k in keep) {
                if (k !in have) c.suffixes.add(Suffix(k, true))
            }
        }
        prefs.edit().putBoolean(KEY_V21_MIGRATED, true).apply()
        return this
    }

    /**
     * 2.2 表情后缀预置（仅首次加载执行一次）：
     *  "喵+表情"作为整体写入句尾附加文字列表（默认关闭，用户勾选即启用；已存在的不重复添加）
     */
    private fun MutableList<Category>.migrateToV22Suffixes(prefs: android.content.SharedPreferences): MutableList<Category> {
        if (prefs.getBoolean(KEY_V22_MIGRATED, false)) return this
        for (c in this) {
            val have = c.suffixes.map { it.text }.toMutableSet()
            for (p in PRESET_EMOTICON_SUFFIXES) {
                if (p !in have) c.suffixes.add(Suffix(p, false))
            }
        }
        prefs.edit().putBoolean(KEY_V22_MIGRATED, true).apply()
        return this
    }

    private fun MutableList<Category>.ensureOnlyOneActive(): MutableList<Category> {
        if (none { it.active }) {
            if (isNotEmpty()) this[0].active = true
        }
        return this
    }

    fun defaultCategories(suffix: String = DEFAULT_SUFFIX): MutableList<Category> = mutableListOf(
        Category(
            "猫娘",
            mutableListOf(
                // 默认开启的子串替换
                Rule("你", "主人", true),
                Rule("好的", "好滴喵", true),
                Rule("嗯嗯", "明白啦喵", true),
                Rule("是的", "是滴喵", true),
                // 默认开启的整句替换
                Rule("早上好", "主人早上好喵", true, true),
                Rule("中午好", "主人中午好喵", true, true),
                Rule("晚上好", "主人晚上好喵", true, true),
                Rule("我靠", "我喵了个咪？！", true, true),
                Rule("浑身颤抖", "喵喵颤抖喵", true, true),
                Rule("不信", "骗喵的吧？！", true, true)
            ),
            active = true,
            suffixes = mutableListOf(
                // 原有默认开启后缀
                Suffix("喵～", true),
                Suffix("喵！", true),
                Suffix("喵呜～", true),
                // 新增默认开启后缀
                Suffix("喵", true),
                Suffix("喵(≧ω≦)", true),
                Suffix("喵៸៸᳐>⩊<៸៸᳐ฅ", true),
                Suffix("喵ꉂ ᳐˶ᵒ ᵕ ˂˶ ᳐ฅ", true),
                Suffix("喵ฅ(≧▽≦)ฅ", true),
                Suffix("喵^ ̳ට ̫ ට ̳^", true),
                // 原有默认关闭后缀
                Suffix("喵喵", false),
                Suffix("喵喵喵～", false),
                Suffix("～喵", false),
                Suffix("(≧ω≦)", false),
                Suffix("(๑•̀ㅂ•́)و✧", false),
                Suffix("ฅ(♡ơ ₃ơ)ฅ", false),
                Suffix("喵呜♡", false)
            ).apply {
                // 预置"喵+表情"整体后缀（默认关闭，用户勾选即启用）
                for (p in PRESET_EMOTICON_SUFFIXES) add(Suffix(p, false))
            }
        ),
        Category("蛋", mutableListOf(), false, mutableListOf()),
        Category("测试1", mutableListOf(), false, mutableListOf()),
        Category("测试2", mutableListOf(), false, mutableListOf()),
        Category("测试3", mutableListOf(), false, mutableListOf())
    )

    /**
     * 预设「更喵的娘」分类（词条来自 奶茶小义 的猫娘化脚本）。
     * 冲突时以「撒娇模式」的词条为准：从脚本的 撒娇替换 中，把所有可安全用于
     * 子串替换的词条全量加入（脚本的编句反应词与子串模型不兼容，故未纳入）。
     */
    fun miao2Preset(): Category {
        // 撒娇模式的字符/短语【子串替换】词条
        val rules = mutableListOf(
            Rule("什么", "什喵", true),
            Rule("怎么", "怎么了", true),
            Rule("在吗", "在嘛", true),
            Rule("不要啊", "不要呀", true),
            Rule("他", "她", true),
            Rule("你", "您", true),
            Rule("我", "咱", true),
            Rule("干什么", "干嘛呀喵", true),
            Rule("干嘛", "干嘛呀喵", true),
            Rule("怎么了", "怎么了嘛喵", true),
            Rule("咋了", "怎么了嘛喵", true),
            Rule("为什么", "为什喵呀", true),
            Rule("快点", "快快哒喵", true),
            Rule("真棒", "好厉害呀喵", true),
            Rule("厉害", "好厉害呀喵", true),
            Rule("吃饭", "去吃好吃的喵", true),
            Rule("宝宝", "主人喵", true)
        )
        // 脚本"撒娇替换"里的【整句反应词】：整条消息等于 from 时，整句替换成随机候选
        val wholeReplies = listOf(
            listOf("好", "好的", "行", "可以", "没问题", "ok", "okk", "欧克") to
                listOf("好哒喵～", "嗯嗯，知道啦喵！", "没问题喵～", "好滴喵～(✿◡‿◡)", "交给猫猫吧喵！"),
            listOf("嗯", "嗯嗯", "哦", "哦哦", "懂了", "明白了", "知道") to
                listOf("嗯呐喵～", "收到啦喵～", "猫猫明白啦喵！", "懂了喵～乖巧点头喵"),
            listOf("对", "是的", "没错") to
                listOf("对哒喵！", "没错喵～", "就是这样喵！"),
            listOf("不", "不行") to
                listOf("这个不行哒喵...", "不行的喵！", "不...不行嘛喵～(っ°Д°;)っ"),
            listOf("不要") to
                listOf("唔...不要嘛喵～", "不要嘛喵～(っ°Д°;)っ", "不用啦谢谢喵"),
            listOf("不可以", "达咩", "拒绝", "不同意") to
                listOf("这个不行哒喵...", "不可以哦喵～", "呜...猫猫拒绝喵！", "达咩喵!"),
            listOf("不知道", "不清楚", "不晓得") to
                listOf("唔...这个猫猫也不知道喵...", "歪头喵？不太清楚耶喵...", "猫猫的脑袋转不动了喵～"),
            listOf("没有", "没") to
                listOf("没有哦喵～", "没看到呢喵～", "完全没有喵！"),
            listOf("累", "好累", "累了", "累死我了", "心累") to
                listOf("呜喵...累瘫了喵...", "想趴着一动不动喵...", "已经是一只废猫了喵...", "需要能量补充喵..."),
            listOf("饿了", "想吃饭", "干饭", "恰饭") to
                listOf("肚子咕噜噜了喵～", "想吃小鱼干了喵！", "要去干饭了嘛喵？"),
            listOf("困", "困了", "想睡觉") to
                listOf("困得睁不开眼了喵...", "想钻进被窝喵～", "呼呼...快睡着了喵..."),
            listOf("开心", "好开心", "真棒", "爽") to
                listOf("好开心呀喵！(≧∇≦)ﾉ", "转圈圈喵～", "开心地摇起了尾巴喵！"),
            listOf("烦", "烦死了", "好烦", "难受", "emo了") to
                listOf("呜...心情不美丽了喵...", "烦恼快飞走喵！", "想找个角落缩起来喵..."),
            listOf("在吗", "在不在") to
                listOf("喵呜？在吗在吗喵？", "探出猫猫头喵...喵？", "喵呜？在吗喵？"),
            listOf("有人吗") to
                listOf("喵呜？有人吗喵？", "探出猫猫头喵...有人吗喵？"),
            listOf("谢谢", "多谢", "谢了", "感谢") to
                listOf("谢谢你呀喵～", "嘿嘿喵，你真好喵！", "蹭蹭喵，谢谢啦喵～"),
            listOf("对不起", "抱歉", "我错了", "不好意思") to
                listOf("呜呜...我错啦喵...", "垂下猫耳...原谅我好不好嘛喵...", "对不起嘛喵..."),
            listOf("哈哈", "哈哈哈", "笑死", "笑死我了") to
                listOf("嘿嘿嘿喵～", "好有趣喵！哈哈哈喵～", "笑得满地打滚喵～"),
            listOf("拜拜", "再见", "走了", "下了") to
                listOf("拜拜喵～要早点回来找我玩喵！", "挥挥猫爪喵，下次见喵～", "晚安安喵，梦里见喵～"),
            listOf("上号", "来了") to
                listOf("上号上号喵！带带我喵～", "来啦来啦，等等猫猫嘛喵"),
            listOf("开黑", "玩游戏") to
                listOf("来不来陪猫猫玩游戏喵？"),
            listOf("666", "6", "六六六", "厉害", "强", "牛逼", "牛批", "nb", "n b", "牛b") to
                listOf("哇哦喵！太强了喵！", "崇拜的眼神喵～", "这就是大佬吗喵？", "好厉害呀喵！", "猫猫佩服喵～"),
            listOf("草", "woc", "卧槽", "绝了") to
                listOf("诶诶喵？！", "居然是这样喵...", "大吃一惊喵！"),
            listOf("笨蛋", "傻瓜", "蠢货") to
                listOf("呜...才不是笨蛋呢喵！", "你才是大笨蛋喵！", "人家一点也不笨喵～"),
            listOf("喜欢", "爱你", "么么") to
                listOf("喵～人家也喜欢你喵！", "害羞惹...谢谢喵～", "蹭蹭喵～好开心喵！"),
            listOf("晚安") to
                listOf("晚安喵～做个好梦喵！", "晚安安喵～明天见喵～", "盖好被子喵～梦里见喵！"),
            listOf("早安", "早上好") to
                listOf("早安喵～新的一天喵！", "早上好呀喵～今天也要加油喵！", "喵喵～早起的猫猫有鱼吃喵！"),
            listOf("你好", "哈喽", "hello", "hi") to
                listOf("你好呀喵～想要贴贴喵", "哈喽哈喽喵～摇尾巴喵", "你好喵～今天也是充满活力的一天喵！"),
            listOf("晚上好", "晚好") to
                listOf("晚上好呀喵～今天辛苦啦喵！", "晚上好呀喵！")
        )
        rules.addAll(
            wholeReplies.flatMap { (keys, vals) ->
                keys.map { Rule(it, vals.joinToString(Rule.SEP), true, true) }
            }
        )
        return Category(
            "更喵的娘",
            rules,
            active = false,
            suffixes = mutableListOf(
                // 脚本的 自动加缀 词条（原有默认开启）
                Suffix("喵～", true),
                Suffix("呀喵～", true),
                Suffix("嘛喵～", true),
                Suffix("的说喵～", true),
                Suffix("呜喵～", true),
                // 新增默认开启后缀
                Suffix("喵", true),
                Suffix("喵(≧ω≦)", true),
                Suffix("喵៸៸᳐>⩊<៸៸᳐ฅ", true),
                Suffix("喵ꉂ ᳐˶ᵒ ᵕ ˂˶ ᳐ฅ", true),
                Suffix("喵ฅ(≧▽≦)ฅ", true),
                Suffix("喵^ ̳ට ̫ ට ̳^", true)
            )
        )
    }

    /** 确保存在"更喵的娘"预设分类：缺失时补一个，不激活。已存在则保留用户的改动，不覆盖。 */
    private fun MutableList<Category>.ensureMiao2Preset(): MutableList<Category> {
        if (none { it.name == "更喵的娘" }) add(miao2Preset())
        return this
    }

    /** 预设的猫娘分类（不默认启用，方便切换到猫娘口癖） */
    fun catgirlPreset(): Category = Category(
        "猫娘",
        mutableListOf(
            Rule("我", "本喵", true),
            Rule("你", "主人", true),
            Rule("啊", "喵", true),
            Rule("呢", "喵", true),
            Rule("吗", "喵", true),
            Rule("嗯", "喵", true),
            Rule("好的", "好喵", true),
            Rule("哈哈", "喵喵喵", true)
        ),
        active = false,
        suffixes = mutableListOf(
            Suffix("喵~", true),
            Suffix("喵喵", true),
            Suffix("喵呜~", true),
            Suffix("喵！", true),
            Suffix("喵喵喵~", true),
            Suffix("～喵", true),
            Suffix("(≧ω≦)", true),
            Suffix("(๑•̀ㅂ•́)و✧", true),
            Suffix("ฅ(♡ơ ₃ơ)ฅ", true),
            Suffix("嘻嘻", true),
            Suffix("喵呜♡", true)
        )
    )

    /** 确保存在"猫娘"预设分类（缺失时补一个，不激活），兼容已有老数据 */
    private fun MutableList<Category>.ensureCatgirlPreset(): MutableList<Category> {
        if (none { it.name == "猫娘" }) add(catgirlPreset())
        return this
    }

    /** 原版 nhy 预设：默认不展示，由彩蛋"？？？"按钮手动加入。 */
    fun nhyPreset(): Category = Category(
        "NHYzhuang",
        mutableListOf(
            Rule("你", "刚刚", false),
            Rule("我", "小薄饼酱", true),
            Rule("的", "跳劈的", true),
            Rule("喜欢", "想要跳劈", true),
            Rule("刻晴", "刻刚", true),
            Rule("qaq", "q刚", true),
            Rule("ad", "a刚", true),
            Rule("小闪", "高冷小刚", true),
            Rule("洗脸", "洗脸刚", true),
            Rule("豆包", "豆刚", true)
        ),
        active = false,
        suffixes = mutableListOf(
            Suffix("嘻嘻嘻嘻哈哈哈哈", true),
            Suffix("风弹重锤起飞眩晕猛击", true),
            Suffix("跳劈跳劈", true),
            Suffix("dml", true)
        )
    )

    /** 重置到默认配置：默认分类 + 「更喵的娘」预设（不包含彩蛋隐藏的 nhy 类）。 */
    fun resetConfig(): Config = Config(defaultCategories().ensureMiao2Preset())

    /** 生成可导出/导入的 JSON 文本。若 categories 为空则导出默认分类。 */
    fun toExportJson(cfg: Config): String {
        val obj = JSONObject()
        val cats = JSONArray()
        val src = if (cfg.categories.isEmpty()) defaultCategories() else cfg.categories
        for (c in src) {
            val cj = JSONObject()
            cj.put("name", c.name)
            cj.put("active", c.active)
            val rArr = JSONArray()
            for (r in c.rules) {
                val rj = JSONObject()
                rj.put("from", r.from)
                rj.put("to", r.to)
                rj.put("enabled", r.enabled)
                rj.put("whole", r.whole)
                rArr.put(rj)
            }
            cj.put("rules", rArr)
            val sArr = JSONArray()
            for (s in c.suffixes) {
                val sj = JSONObject()
                sj.put("text", s.text)
                sj.put("enabled", s.enabled)
                sArr.put(sj)
            }
            cj.put("suffixes", sArr)
            cats.put(cj)
        }
        obj.put("categories", cats)
        return obj.toString(2)
    }

    /** 解析导入文本，格式合法返回 Config（可空表示失败） */
    fun parseImport(text: String): Config? {
        return try {
            val obj = JSONObject(text)
            val cats = mutableListOf<Category>()
            val catsJson = obj.optJSONArray("categories") ?: return null
            for (i in 0 until catsJson.length()) {
                val cj = catsJson.getJSONObject(i)
                val rules = mutableListOf<Rule>()
                val rulesArr = cj.optJSONArray("rules")
                if (rulesArr != null) {
                    for (j in 0 until rulesArr.length()) {
                        val rj = rulesArr.getJSONObject(j)
                        rules.add(
                            Rule(
                                rj.optString("from"),
                                migrateTo(rj.optString("to")),
                                rj.optBoolean("enabled", true),
                                rj.optBoolean("whole", false)
                            )
                        )
                    }
                }
                val suffixes = mutableListOf<Suffix>()
                val sArr = cj.optJSONArray("suffixes")
                if (sArr != null) {
                    for (j in 0 until sArr.length()) {
                        val item = sArr.opt(j)
                        when (item) {
                            is JSONObject -> {
                                val s = item.optString("text")
                                if (s.isNotBlank()) suffixes.add(Suffix(s, item.optBoolean("enabled", true)))
                            }
                            else -> {
                                val s = sArr.optString(j)
                                if (s.isNotBlank()) suffixes.add(Suffix(s, true))
                            }
                        }
                    }
                }
                cats.add(
                    Category(
                        cj.optString("name", "未命名"),
                        rules,
                        cj.optBoolean("active", false),
                        suffixes
                    )
                )
            }
            if (cats.isEmpty()) return null
            Config(cats.ensureOnlyOneActive())
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 白名单应用的存取（3.2 结构重写版）：SQLite allow_table；
 * 旧 SharedPreferences（qq_settings 的 whitelist_json / 旧 extra_apps / clipboard_apps）首次加载时一次性迁移。
 */
object WhitelistStore {
    private const val PREFS = "qq_settings"
    private const val KEY = "whitelist_json"
    // 旧 key，用于一次性迁移
    private const val LEGACY_EXTRA = "extra_apps"
    private const val LEGACY_CLIPBOARD = "clipboard_apps"

    fun load(context: Context): MutableList<AppEntry> {
        val dao = KittyDatabase.get(context).phrases()
        val rows = dao.allowlist()
        if (rows.isNotEmpty()) {
            return rows.map { AppEntry(it.pkg, it.active == 1, it.clipboard == 1, it.label) }.toMutableList()
        }
        // 库空：从旧 SP 迁移
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = mutableListOf<AppEntry>()
        val json = p.getString(KEY, null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        AppEntry(
                            o.getString("pkg"),
                            o.optBoolean("enabled", true),
                            o.optBoolean("clipboard", false),
                            o.optString("label", "")
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        if (list.isEmpty()) {
            val olds = p.getStringSet(LEGACY_EXTRA, emptySet())?.toList() ?: emptyList()
            val clip = p.getStringSet(LEGACY_CLIPBOARD, emptySet())?.toSet() ?: emptySet()
            if (olds.isNotEmpty()) {
                for (pkg in olds) {
                    list.add(AppEntry(pkg, true, pkg in clip, ""))
                }
            }
        }
        if (list.isNotEmpty()) {
            save(context, list)
            p.edit().remove(KEY).remove(LEGACY_EXTRA).remove(LEGACY_CLIPBOARD).apply()
        }
        return list
    }

    fun save(context: Context, list: List<AppEntry>) {
        val dao = KittyDatabase.get(context).phrases()
        val entities = list.map { AllowlistEntity(0, it.pkg, if (it.enabled) 1 else 0, if (it.clipboard) 1 else 0, it.label) }
        dao.replaceAllowlist(entities)
    }
}
