package com.board.ime.database

import androidx.room.TypeConverter
import com.board.ime.clipboard.ClipboardContentType
import com.board.ime.clipboard.ClipboardOrigin
import com.board.ime.dictionary.WordType
import com.board.ime.sync.DevicePlatform
import com.board.ime.sync.SyncEntityType
import com.board.ime.sync.SyncOperationAction
import com.board.ime.sync.SyncOperationStatus

/**
 * 枚举 <-> 字符串转换。
 *
 * 显式写出转换器（而不是依赖 Room 的枚举默认行为），
 * 这样数据库里存的是可读的枚举名，便于将来跨端同步时直接传输。
 */
class Converters {

    @TypeConverter
    fun fromClipboardContentType(value: ClipboardContentType): String = value.name

    @TypeConverter
    fun toClipboardContentType(value: String): ClipboardContentType =
        enumValueOrDefault(value, ClipboardContentType.TEXT)

    @TypeConverter
    fun fromDevicePlatform(value: DevicePlatform): String = value.name

    @TypeConverter
    fun toDevicePlatform(value: String): DevicePlatform =
        enumValueOrDefault(value, DevicePlatform.UNKNOWN)

    @TypeConverter
    fun fromSyncEntityType(value: SyncEntityType): String = value.name

    @TypeConverter
    fun toSyncEntityType(value: String): SyncEntityType =
        enumValueOrDefault(value, SyncEntityType.SETTINGS)

    @TypeConverter
    fun fromSyncOperationAction(value: SyncOperationAction): String = value.name

    @TypeConverter
    fun toSyncOperationAction(value: String): SyncOperationAction =
        enumValueOrDefault(value, SyncOperationAction.UPDATE)

    @TypeConverter
    fun fromSyncOperationStatus(value: SyncOperationStatus): String = value.name

    @TypeConverter
    fun toSyncOperationStatus(value: String): SyncOperationStatus =
        enumValueOrDefault(value, SyncOperationStatus.PENDING)

    @TypeConverter
    fun fromWordType(value: WordType): String = value.name

    @TypeConverter
    fun toWordType(value: String): WordType =
        enumValueOrDefault(value, WordType.CUSTOM)

    @TypeConverter
    fun fromClipboardOrigin(value: ClipboardOrigin): String = value.name

    @TypeConverter
    fun toClipboardOrigin(value: String): ClipboardOrigin =
        enumValueOrDefault(value, ClipboardOrigin.LOCAL)

    @TypeConverter
    fun fromDictionarySourceType(value: com.board.ime.dictionary.DictionarySourceType): String =
        value.name

    @TypeConverter
    fun toDictionarySourceType(value: String): com.board.ime.dictionary.DictionarySourceType =
        enumValueOrDefault(value, com.board.ime.dictionary.DictionarySourceType.IMPORTED)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
