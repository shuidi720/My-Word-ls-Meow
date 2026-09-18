package io.catpaw.kittytalk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** 短语仓库 DAO：分类/规则/句尾/白名单的读写（全量重写式，数据量小） */
@Dao
interface PhraseDao {

    @Query("SELECT * FROM category_table")
    fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM category_table WHERE active = 1")
    fun activeCategories(): List<CategoryEntity>

    @Query("SELECT * FROM phrase_table WHERE cid = :cid ORDER BY seq")
    fun rulesOf(cid: Long): List<RuleEntity>

    @Query("SELECT * FROM tail_table WHERE cid = :cid ORDER BY seq")
    fun suffixesOf(cid: Long): List<SuffixEntity>

    @Query("SELECT * FROM allow_table")
    fun allowlist(): List<AllowlistEntity>

    @Insert
    fun insertCategory(entity: CategoryEntity): Long

    @Insert
    fun insertRule(entity: RuleEntity): Long

    @Insert
    fun insertSuffix(entity: SuffixEntity): Long

    @Insert
    fun insertAllow(entity: AllowlistEntity): Long

    @Query("DELETE FROM category_table")
    fun clearCategories()

    @Query("DELETE FROM phrase_table")
    fun clearRules()

    @Query("DELETE FROM tail_table")
    fun clearSuffixes()

    @Query("DELETE FROM allow_table")
    fun clearAllow()

    @Query("SELECT COUNT(*) FROM category_table")
    fun categoryCount(): Int

    @Query("SELECT COUNT(*) FROM allow_table")
    fun allowCount(): Int

    /** 事务内全量重写分类树（save 用） */
    @Transaction
    fun replaceCategories(categories: List<CategoryEntity>, rules: List<RuleEntity>, suffixes: List<SuffixEntity>) {
        clearCategories()
        clearRules()
        clearSuffixes()
        for (c in categories) insertCategory(c)
        for (r in rules) insertRule(r)
        for (s in suffixes) insertSuffix(s)
    }

    /** 事务内全量重写白名单（save 用） */
    @Transaction
    fun replaceAllowlist(items: List<AllowlistEntity>) {
        clearAllow()
        for (a in items) insertAllow(a)
    }
}
