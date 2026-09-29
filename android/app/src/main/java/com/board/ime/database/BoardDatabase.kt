package com.board.ime.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.board.ime.database.dao.ClipboardDao
import com.board.ime.database.dao.DeviceDao
import com.board.ime.database.dao.DictionaryDao
import com.board.ime.database.dao.DictionarySourceDao
import com.board.ime.database.dao.SyncOperationDao
import com.board.ime.database.entity.ClipboardItem
import com.board.ime.database.entity.Device
import com.board.ime.database.entity.DictionaryEntry
import com.board.ime.database.entity.SyncOperation
import com.board.ime.dictionary.DictionarySource

/**
 * Board 基础数据库。
 *
 * 阶段一只建好四张表和 DAO：
 * - clipboard_item：跨设备剪贴板（后续阶段接入）
 * - dictionary_entry：词库导入导出、词频
 * - device：已配对设备
 * - sync_operation：待同步操作队列
 *
 * 版本 1 尚未对外发布，因此允许破坏性迁移；正式发版后必须改为 Migration。
 */
@Database(
    entities = [
        ClipboardItem::class,
        DictionaryEntry::class,
        DictionarySource::class,
        Device::class,
        SyncOperation::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class BoardDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    abstract fun dictionaryDao(): DictionaryDao

    abstract fun dictionarySourceDao(): DictionarySourceDao

    abstract fun deviceDao(): DeviceDao

    abstract fun syncOperationDao(): SyncOperationDao

    companion object {

        private const val DATABASE_NAME = "board.db"
        private const val TAG = "BoardDatabase"

        /** v2：词库表增加 type 列（学习词 / 自定义词 / 固定词 / 屏蔽词）与其索引 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dictionary_entry ADD COLUMN type TEXT NOT NULL DEFAULT 'CUSTOM'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dictionary_entry_type " +
                        "ON dictionary_entry (type)",
                )
            }
        }

        /**
         * v3：剪贴板表按本轮需求升级
         * （`pinned` → `isPinned`，新增 `isFavorite` / `contentHash` / `isDeleted`）。
         *
         * SQLite 在 minSdk 26 上不支持 `ALTER TABLE ... RENAME COLUMN`，
         * 所以用标准做法：建新表 → 拷数据 → 删旧表 → 改名 → 重建索引。
         * 内容 / 时间戳 / 设备来源全部保留，只补默认值。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS clipboard_item_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        contentType TEXT NOT NULL,
                        preview TEXT,
                        contentHash TEXT NOT NULL DEFAULT '',
                        sourceDeviceId TEXT,
                        isPinned INTEGER NOT NULL DEFAULT 0,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        isDeleted INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deletedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO clipboard_item_new
                        (id, content, contentType, preview, contentHash, sourceDeviceId,
                         isPinned, isFavorite, isDeleted, createdAt, updatedAt, deletedAt)
                    SELECT id, content, contentType, preview, '', sourceDeviceId,
                           pinned, 0, CASE WHEN deletedAt IS NULL THEN 0 ELSE 1 END,
                           createdAt, updatedAt, deletedAt
                    FROM clipboard_item
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE clipboard_item")
                db.execSQL("ALTER TABLE clipboard_item_new RENAME TO clipboard_item")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_contentHash " +
                        "ON clipboard_item (contentHash)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_createdAt " +
                        "ON clipboard_item (createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_updatedAt " +
                        "ON clipboard_item (updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_sourceDeviceId " +
                        "ON clipboard_item (sourceDeviceId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_deletedAt " +
                        "ON clipboard_item (deletedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_isDeleted " +
                        "ON clipboard_item (isDeleted)",
                )
            }
        }

        /**
         * v4：剪贴板增加「来源」字段，用来区分本机产生的和从其他设备同步来的。
         *
         * - `origin`：LOCAL / REMOTE（默认 LOCAL，历史数据全部算本机）
         * - `originDeviceId` / `originType` / `receivedAt`：远端来源信息，允许为空
         *
         * 全部是 ADD COLUMN，旧数据（内容、时间、固定收藏）原样保留。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clipboard_item ADD COLUMN origin TEXT NOT NULL DEFAULT 'LOCAL'",
                )
                db.execSQL("ALTER TABLE clipboard_item ADD COLUMN originDeviceId TEXT")
                db.execSQL("ALTER TABLE clipboard_item ADD COLUMN originType TEXT")
                db.execSQL("ALTER TABLE clipboard_item ADD COLUMN receivedAt INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_item_origin " +
                        "ON clipboard_item (origin)",
                )
            }
        }

        /**
         * v5：为「词库 / 学习词 / 常用语同步」补齐数据模型。
         *
         * - `dictionary_entry`：加墓碑字段（删除可以传播，不会被另一台设备恢复）
         * - `sync_operation`：加 deviceId / deleted / timestamp（同步任务需要）
         *
         * 全部是 ADD COLUMN，老数据一条不丢。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dictionary_entry ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE dictionary_entry ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE dictionary_entry ADD COLUMN sourceDeviceId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dictionary_entry_isDeleted " +
                        "ON dictionary_entry (isDeleted)",
                )

                db.execSQL("ALTER TABLE sync_operation ADD COLUMN deviceId TEXT")
                db.execSQL(
                    "ALTER TABLE sync_operation ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE sync_operation ADD COLUMN timestamp INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v6：新增「词库数据源」表。
         *
         * 大型导入词库以后只在这个表里占一行（文件路径 + hash + 条数），
         * 不再往 `dictionary_entry` 灌几十万行。表结构必须与 Room 的期望完全一致。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dictionary_source (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'IMPORTED',
                        path TEXT NOT NULL,
                        hash TEXT NOT NULL DEFAULT '',
                        entryCount INTEGER NOT NULL DEFAULT 0,
                        sizeBytes INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        format TEXT NOT NULL DEFAULT 'RIME_DICT',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dictionary_source_type " +
                        "ON dictionary_source (type)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dictionary_source_enabled " +
                        "ON dictionary_source (enabled)",
                )
            }
        }

        /**
         * v7：词库数据源补齐「跨设备文件同步」需要的字段。
         *
         * - `sourceId`：稳定 ID（由名字派生），用作云端目录名，两台设备对得上同一个词库
         * - `version`：内容版本号，内容变化时 +1
         * - `ready`：是否已生成 Rime 词典配置并部署成功
         *
         * 全部是 ADD COLUMN，老数据（路径 / hash / 条数 / 大小）一条不丢；
         * `sourceId` 对老行留空，由仓库层按名字补齐（幂等）。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dictionary_source ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE dictionary_source ADD COLUMN version INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE dictionary_source ADD COLUMN ready INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dictionary_source_sourceId " +
                        "ON dictionary_source (sourceId)",
                )
            }
        }

        @Volatile
        private var instance: BoardDatabase? = null

        fun get(context: Context): BoardDatabase =
            instance ?: synchronized(this) {
                instance ?: openWithRecovery(context).also { instance = it }
            }

        /**
         * 打开数据库，失败时**自愈**而不是让 App 崩掉。
         *
         * 真机上可能遇到：老版本留下的 schema 与迁移脚本对不上、DB 文件损坏、
         * 外部存储异常等。这些都不该让「点开设置页」直接闪退，所以：
         * 1. 先按正常流程打开（含所有迁移）
         * 2. 任何 Throwable → 记日志，删掉数据库文件重建一次
         *    （词条/剪贴板历史会丢，但 Rime 的学习数据在 files/rime/user 里，不受影响）
         */
        private fun openWithRecovery(context: Context): BoardDatabase {
            if (recovering) {
                // 二次失败：直接给一个可用的空库，绝不抛到调用方
                return build(context)
            }
            return try {
                val db = build(context)
                // 关键：Room 是**懒加载**的，不主动碰一次的话，
                // 迁移校验失败不会在这里抛，而是变成「之后每次查询都抛、
                // 但 App 不崩也不自愈」的半坏状态（真机上就是这种表现）。
                db.openHelper.writableDatabase
                db
            } catch (throwable: Throwable) {
                Log.e(TAG, "打开数据库失败，尝试重建：${throwable.message}", throwable)
                recovering = true
                runCatching { context.deleteDatabase(DATABASE_NAME) }
                build(context)
            }
        }

        @Volatile
        private var recovering = false

        private fun build(context: Context): BoardDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                BoardDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
