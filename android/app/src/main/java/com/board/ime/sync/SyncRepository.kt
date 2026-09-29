package com.board.ime.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步通道抽象。
 *
 * 阶段一只保留接口与占位实现，明确“同步能力”将来挂在哪里，
 * 但不引入任何网络代码（WebDAV / 局域网 / 账号都不在本阶段范围内）。
 */
interface SyncRepository {

    /** 同步状态，供设置页 / 候选栏的同步指示使用（强调色的小圆点） */
    fun observeState(): Flow<SyncState>

    /** 主动触发一次同步；返回是否真正发起了同步 */
    suspend fun requestSync(): Boolean
}

/** 阶段一占位实现：永远处于空闲状态，不做任何网络动作 */
class NoOpSyncRepository : SyncRepository {

    private val state = MutableStateFlow(SyncState.IDLE)

    override fun observeState(): Flow<SyncState> = state.asStateFlow()

    override suspend fun requestSync(): Boolean = false
}
