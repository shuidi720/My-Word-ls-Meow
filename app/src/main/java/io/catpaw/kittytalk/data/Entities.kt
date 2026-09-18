package io.catpaw.kittytalk.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 分类表：一个分类下挂规则与句尾附加文字 */
@Entity(tableName = "category_table")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val cid: Long = 0,
    val name: String,
    val active: Int
)

/** 规则表（子串替换 / 整句匹配） */
@Entity(tableName = "phrase_table")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val rid: Long = 0,
    val cid: Long,
    val trigger: String,
    val replacement: String,
    val active: Int,
    val whole: Int,
    val exclude: Int,
    val seq: Int
)

/** 句尾附加文字表 */
@Entity(tableName = "tail_table")
data class SuffixEntity(
    @PrimaryKey(autoGenerate = true) val sid: Long = 0,
    val cid: Long,
    val content: String,
    val active: Int,
    val seq: Int
)

/** 白名单应用表 */
@Entity(tableName = "allow_table")
data class AllowlistEntity(
    @PrimaryKey(autoGenerate = true) val aid: Long = 0,
    val pkg: String,
    val active: Int,
    val clipboard: Int,
    val label: String
)
