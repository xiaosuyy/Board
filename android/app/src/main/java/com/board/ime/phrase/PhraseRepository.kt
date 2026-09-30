package com.board.ime.phrase

import com.board.ime.database.dao.PhraseDao
import com.board.ime.database.entity.PhraseEntry
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 常用语仓库。
 *
 * 身份规则：本机用自增 `id`，**跨设备只用 `phraseId`**（UUID）。
 * 因此两台设备各自自增出的 `id=1` 永远不会被当成同一条；
 * 而「不同 phraseId、正文一模一样」的两条可以并存（表对 `phraseId` 建唯一索引，不对正文建）。
 */
class PhraseRepository(private val dao: PhraseDao) {

    /** 正常列表（墓碑不出现），按用户顺序 */
    fun observe(): Flow<List<PhraseEntry>> = dao.observeActive()

    suspend fun count(): Int = dao.countActive()

    suspend fun all(): List<PhraseEntry> = dao.all()

    /**
     * 新增一条常用语。
     *
     * - 前后空白去掉；空文本拒绝保存（返回 null）；
     * - 生成**稳定 phraseId**；
     * - 默认排在最前面（sortOrder 取当前最小值 - 1）。
     */
    suspend fun add(text: String, sourceDeviceId: String? = null): String? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        val now = System.currentTimeMillis()
        val minOrder = dao.all()
            .filter { it.deletedAt == null }
            .minOfOrNull { it.sortOrder } ?: 0
        val entry = PhraseEntry(
            phraseId = UUID.randomUUID().toString(),
            text = normalized,
            sortOrder = minOrder - 1,
            createdAt = now,
            updatedAt = now,
            sourceDeviceId = sourceDeviceId,
        )
        dao.insert(entry)
        return entry.phraseId
    }

    /**
     * 编辑正文：**保留 phraseId 与排序位置**，只更新 text + updatedAt。
     * 返回是否真的修改了（空文本 / 内容没变都返回 false，避免无意义同步）。
     */
    suspend fun updateText(phraseId: String, text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        val existing = dao.findByPhraseId(phraseId) ?: return false
        if (existing.deletedAt != null) return false
        if (existing.text == normalized) return false
        val now = System.currentTimeMillis()
        dao.updateText(phraseId, normalized, now, existing.sourceDeviceId)
        return true
    }

    /** 软删除（墓碑），删除才会被同步传播，不会被另一台设备复活 */
    suspend fun delete(phraseId: String): Boolean {
        val existing = dao.findByPhraseId(phraseId) ?: return false
        if (existing.deletedAt != null) return false
        val now = System.currentTimeMillis()
        dao.markDeleted(phraseId, now, now)
        return true
    }

    /**
     * 拖动排序：传入**最终的 phraseId 顺序**。
     *
     * 一次性归一化成 0..n-1 写回；只有顺序真的变了才返回 true
     * （避免拖动过程中的每一帧都写库 / 触发同步）。
     */
    suspend fun reorder(orderedPhraseIds: List<String>): Boolean {
        val active = dao.all().filter { it.deletedAt == null }
        val current = active.sortedWith(
            compareBy({ it.sortOrder }, { it.updatedAt }, { it.phraseId }),
        ).map { it.phraseId }
        if (current == orderedPhraseIds) return false
        orderedPhraseIds.forEachIndexed { index, phraseId ->
            dao.updateSortOrder(phraseId, index)
        }
        return true
    }

    /**
     * 多设备同步 + 排序冲突归一化。
     *
     * 规则（与现有 [com.board.ime.sync.SyncWordMerge] 一致，只是 key 换成 phraseId）：
     * - 只认 phraseId；同一 phraseId 才做合并；
     * - 墓碑与活条目冲突：`updatedAt` 较新者胜（删除不会被旧数据复活）；
     * - 两边都活着：较新的一侧胜；
     * - 合并后若出现相同 sortOrder：**不丢任何条目**，按
     *   `sortOrder → updatedAt → phraseId` 稳定重排成本机连续顺序。
     */
    suspend fun mergeFromSync(remote: List<PhraseEntry>) {
        remote.forEach { incoming ->
            if (incoming.phraseId.isBlank()) return@forEach
            val local = dao.findByPhraseId(incoming.phraseId)
            if (local == null) {
                dao.insert(incoming.copy(id = 0L))
            } else if (newer(incoming, local) === incoming) {
                dao.overwrite(
                    phraseId = local.phraseId,
                    text = incoming.text,
                    sortOrder = incoming.sortOrder,
                    deletedAt = incoming.deletedAt,
                    createdAt = incoming.createdAt,
                    updatedAt = incoming.updatedAt,
                    sourceDeviceId = incoming.sourceDeviceId,
                )
            }
        }
        normalizeOrder()
    }

    /** 本机连续顺序（不丢条目）：sortOrder → updatedAt → phraseId */
    private suspend fun normalizeOrder() {
        val active = dao.all()
            .filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.updatedAt }, { it.phraseId }))
        active.forEachIndexed { index, entry ->
            if (entry.sortOrder != index) dao.updateSortOrder(entry.phraseId, index)
        }
    }

    private fun newer(a: PhraseEntry, b: PhraseEntry): PhraseEntry = when {
        // 墓碑按时间判胜负：删除除非被"更新的活着版本"盖过，否则一直生效
        (a.deletedAt != null) != (b.deletedAt != null) ->
            if (a.deletedAt != null && a.updatedAt >= b.updatedAt) a
            else if (b.deletedAt != null && b.updatedAt >= a.updatedAt) b
            else if (a.deletedAt != null) b else a

        else -> if (a.updatedAt >= b.updatedAt) a else b
    }
}
