package io.catpaw.kittytalk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 短语与白名单数据库（SQLite 存储模型，替代旧 SharedPreferences+JSON）。
 * 版本升级策略：
 *  - 不使用 fallbackToDestructiveMigration()，避免升级静默清空用户数据；
 *  - 提供显式回退（handleDestructiveMigration）仅在无法迁移时降级到"重导默认配置"，
 *    同时保留旧 SharedPreferences 里的一次性迁移（见 SettingsRepository.load）。
 */
@Database(
    entities = [CategoryEntity::class, RuleEntity::class, SuffixEntity::class, AllowlistEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KittyDatabase : RoomDatabase() {

    abstract fun phrases(): PhraseDao

    companion object {
        private const val DB_NAME = "kittytalk.db"

        @Volatile
        private var instance: KittyDatabase? = null

        fun get(context: Context): KittyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, KittyDatabase::class.java, DB_NAME)
                    .allowMainThreadQueries()
                    // 不静默清库；仅在架构变更导致无法打开时显式降级（仍保留 SP 迁移路径）
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // 建库成功：触发旧 SharedPreferences 一次性迁移到 SQLite
                            // （实际迁移逻辑在 SettingsRepository.load / WhitelistStore.load 中完成）
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
