package com.board.ime.cloudlibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library UI 契约（统一混合列表 + 本机隐藏 + 增量缩略图；纯 JVM） */
class LibraryUiModelTest {

    private val me = "android-1"
    private val other = "win-1"

    private fun image(hash: String, at: Long = 10, deviceId: String = other) = CloudLibraryItem(
        hash = hash, type = "image", name = "p.png", size = 2048, uploadedAt = at, deviceId = deviceId,
    )

    private fun file(hash: String, at: Long = 20, deviceId: String = other) = CloudLibraryItem(
        hash = hash, type = "file", name = "a.bin", size = 512, uploadedAt = at, deviceId = deviceId,
    )

    private val hA = "a".repeat(64)
    private val hB = "b".repeat(64)
    private val hC = "c".repeat(64)

    // 1) 图片 + 文件混排（不再分区）
    @Test
    fun imageAndFileShareOneList() {
        val rows = LibraryUiModel.rows(listOf(image(hA), file(hB)), me)
        assertEquals(2, rows.size)
        // 混排：一张图片一个文件都在同一列表里
        assertEquals(1, rows.count { it.isImage })
        assertEquals(1, rows.count { !it.isImage })
    }

    // 2) uploadedAt DESC
    @Test
    fun sortedByUploadedAtDescending() {
        val rows = LibraryUiModel.rows(
            listOf(image(hA, at = 100), file(hB, at = 900), image(hC, at = 500)),
            me,
        )
        assertEquals(listOf(hB, hC, hA), rows.map { it.item.hash })
    }

    // 3) uploadedAt 相同 → hash 稳定排序（刷新不抖动）
    @Test
    fun equalUploadedAtIsStable() {
        val one = LibraryUiModel.rows(listOf(image(hC, at = 7), file(hA, at = 7), image(hB, at = 7)), me)
        val two = LibraryUiModel.rows(listOf(image(hB, at = 7), file(hA, at = 7), image(hC, at = 7)), me)
        assertEquals(listOf(hA, hB, hC), one.map { it.item.hash })
        assertEquals("同一 uploadedAt 必须顺序稳定", one.map { it.item.hash }, two.map { it.item.hash })
    }

    // 4/5) hidden 过滤
    @Test
    fun hiddenHashesAreFilteredOut() {
        val items = listOf(image(hA), file(hB))
        assertEquals(2, LibraryUiModel.rows(items, me).size)
        val shown = LibraryUiModel.rows(items, me, hiddenHashes = setOf(hA))
        assertEquals(1, shown.size)
        assertEquals(hB, shown[0].item.hash)
    }

    // 6) 隐藏一项后列表立即减少（模型层）
    @Test
    fun hidingOneRemovesItImmediately() {
        val items = listOf(image(hA), file(hB), image(hC))
        assertEquals(3, LibraryUiModel.rows(items, me).size)
        assertEquals(2, LibraryUiModel.rows(items, me, hiddenHashes = setOf(hB)).size)
    }

    // 8/9) 重新上传解除 hidden 由调用方处理；模型只做过滤
    @Test
    fun unhideRestoresVisibility() {
        val items = listOf(image(hA))
        assertTrue(LibraryUiModel.rows(items, me, hiddenHashes = setOf(hA)).isEmpty())
        assertTrue(LibraryUiModel.rows(items, me, hiddenHashes = emptySet()).isNotEmpty())
    }

    // 10/11/12) 缩略图增量：复用 / 只加新 / 消失即淘汰
    @Test
    fun thumbnailCacheIsIncremental() {
        val first = listOf(image(hA), image(hB))
        val live1 = LibraryUiModel.liveHashes(first)
        val cache = mapOf(hA to "A", hB to "B")
        assertTrue("已有 hash 不得重下", LibraryUiModel.staleThumbnailKeys(cache, live1).isEmpty())

        // 新增 C：只有 C 需要新加载（A/B 复用）
        val second = listOf(image(hA), image(hB), image(hC))
        val live2 = LibraryUiModel.liveHashes(second)
        assertEquals(setOf(hA, hB, hC), live2)
        assertEquals(setOf(hC), live2 - cache.keys)

        // B 云端消失：只淘汰 B
        val third = listOf(image(hA), image(hC))
        assertEquals(listOf(hB), LibraryUiModel.staleThumbnailKeys(cache, LibraryUiModel.liveHashes(third)))
    }

    @Test
    fun liveHashesOnlyCoversImages() {
        assertTrue(LibraryUiModel.liveHashes(listOf(file(hA))).isEmpty())
        assertEquals(setOf(hA), LibraryUiModel.liveHashes(listOf(image(hA))))
    }

    // 13) 不再有分区概念（结构断言：源码里已无 LibrarySection/rowsFor）
    @Test
    fun noSectionConceptRemains() {
        val text = classBytes("com/board/ime/cloudlibrary/LibraryUiModel.class")
        assertFalse("不应再有 LibrarySection", text.contains("LibrarySection"))
        assertFalse("不应再有 rowsFor", text.contains("rowsFor"))
        assertFalse("不应再有 swipeTarget", text.contains("swipeTarget"))
        assertFalse("不应再有 sectionLabel", text.contains("sectionLabel"))
    }

    // 14/15/16) Clipboard 只展示文字；且公开回调保留
    @Test
    fun clipboardPanelIsTextOnlyAndKeepsPublicCallbacks() {
        val text = classBytes("com/board/ime/ui/panel/ClipboardPanel.class")
        assertFalse("分类枚举 ClipPage 必须已移除", text.contains("ClipPage"))
        // 公开回调必须保留（宿主仍依赖；删 UI 不等于删 API）
        assertTrue(text.contains("onLoadThumbnail"))
        assertTrue(text.contains("onSaveImage"))
        assertTrue(text.contains("onLoadContent"))
        assertTrue(text.contains("onInsert"))
    }

    // 来源 / 大小 / 预览能力
    @Test
    fun sourceSizeAndPreviewContract() {
        val img = LibraryUiModel.toRow(image(hA), me, "Windows")
        assertEquals("云端 · Windows", img.sourceLabel)
        assertFalse(img.isLocal)
        assertTrue("图片可预览", img.canPreview)
        assertEquals("2.0 KB", img.sizeLabel)

        val f = LibraryUiModel.toRow(file(hB), me, "Windows")
        assertFalse("文件绝不预览", f.canPreview)
        assertEquals("云端 · Windows", f.sourceLabel)

        val mine = LibraryUiModel.toRow(image(hC, deviceId = me), me, "Android")
        assertEquals("本地", mine.sourceLabel)
        assertTrue(mine.isLocal)
    }

    @Test
    fun emptyTextReflectsThreeStates() {
        assertEquals("暂无资料", LibraryUiModel.emptyText(LibrarySnapshot()))
        assertEquals(
            "部分设备暂时不可读",
            LibraryUiModel.emptyText(LibrarySnapshot(items = listOf(image(hA)), partial = true, error = LibraryError.PARTIAL)),
        )
        assertEquals(
            "网络不可用",
            LibraryUiModel.emptyText(LibrarySnapshot(items = listOf(image(hA)), error = LibraryError.NETWORK)),
        )
    }

    @Test
    fun downloadOutcomeMessages() {
        assertEquals("✓ 已下载到相册", DownloadOutcome.ImageSaved.message)
        assertEquals("✓ 已下载", DownloadOutcome.FileSaved.message)
        assertEquals("× 下载失败", DownloadOutcome.Failed(LibraryError.NETWORK).message)
    }

    @Test
    fun oversizedUploadRejected() {
        assertFalse(CloudLibrary.withinLimit(CloudLibrary.MAX_LIBRARY_BYTES + 1))
        assertTrue(CloudLibrary.withinLimit(CloudLibrary.MAX_LIBRARY_BYTES))
    }

    @Test
    fun autoRefreshIntervalIsLowFrequency() {
        assertEquals(60_000L, LibraryUiModel.AUTO_REFRESH_MS)
    }

    // ---- Phase 2.3：metadata-only（本机有 metadata、无正文）**必须可见** ----
    /** 构造一条「本机只有 metadata」的条目（contentCached=false 的等价物） */
    private fun metadataOnly(hash: String, at: Long = 1000) = CloudLibraryItem(
        hash = hash, type = "image", name = "orphan.png", size = 81117, uploadedAt = at, deviceId = other,
    )

    @Test
    fun metadataOnlyLocalItemIsVisibleOffline() {
        // 本机有 metadata、无正文；云端为空（断网 / index 不可达）
        val local = listOf(metadataOnly(hA))
        val merged = LibraryMerge.merge(local, emptyList())
        val rows = LibraryUiModel.rows(merged, me)
        assertEquals("metadata-only 条目断网也必须可见", 1, rows.size)
        assertEquals(hA, rows[0].item.hash)
        assertEquals("orphan.png", rows[0].name)
        assertEquals("79.2 KB", rows[0].sizeLabel)
        assertTrue("图片仍具预览能力", rows[0].canPreview)
        assertTrue(rows[0].canDownload)
    }

    @Test
    fun metadataOnlySurvivesCloudRemoval() {
        val local = listOf(metadataOnly(hA), image(hB, at = 900))
        val merged = LibraryMerge.merge(local, emptyList())
        assertEquals(2, merged.size)
        assertEquals(2, LibraryUiModel.rows(merged, me).size)
    }

    @Test
    fun metadataOnlyDoesNotRequireDecode() {
        // rows() 是纯逻辑：不需要任何 Bitmap / decode 就能产出 row
        val rows = LibraryUiModel.rows(LibraryMerge.merge(listOf(metadataOnly(hA)), emptyList()), me)
        assertEquals(1, rows.size)
        assertTrue(rows[0].isImage)
    }

    @Test
    fun cachedContentReusesLocalBytes() {
        // 模型层：row 的生成与 uploadedAt/type/name 有关，与“正文是否在本机”无关
        val withContent = image(hA, at = 100)
        val withoutContent = metadataOnly(hA, at = 100)
        assertEquals(
            LibraryUiModel.rows(listOf(withContent), me).size,
            LibraryUiModel.rows(listOf(withoutContent), me).size,
        )
    }

    @Test
    fun hiddenMetadataOnlyItemRemainsCached() {
        val merged = LibraryMerge.merge(listOf(metadataOnly(hA)), emptyList())
        assertTrue("隐藏后不显示", LibraryUiModel.rows(merged, me, hiddenHashes = setOf(hA)).isEmpty())
        assertTrue("但 metadata 未被删除", LibraryUiModel.rows(merged, me).isNotEmpty())
    }

    @Test
    fun cloudAndLocalUnionKeepsBoth() {
        // 本机 3 条（其中 1 条 metadata-only）+ 云端新增 1 条
        val local = listOf(metadataOnly(hC, at = 10), image(hA, at = 300), file(hB, at = 200))
        val cloud = listOf(file("d".repeat(64), at = 900))
        val merged = LibraryMerge.merge(local, cloud)
        val rows = LibraryUiModel.rows(merged, me)
        assertEquals(4, rows.size)
        assertEquals("最新在顶部", "d".repeat(64), rows[0].item.hash)
        assertTrue("本机 metadata-only 仍在", rows.any { it.item.hash == hC })
    }

    // ---- Phase 2.1：本机 Library 缓存 ∪ 云端 ----

    @Test
    fun localCacheSurvivesCloudTtlRemoval() {
        val cached = listOf(image(hA, at = 100))
        val merged = LibraryMerge.merge(cached, emptyList())
        assertEquals("云端 TTL 消失后本机已收到的内容必须保留", 1, merged.size)
        assertEquals(hA, merged[0].hash)
    }

    @Test
    fun cloudNewerMetadataWins() {
        val merged = LibraryMerge.merge(listOf(image(hA, at = 100)), listOf(image(hA, at = 900)))
        assertEquals(1, merged.size)
        assertEquals(900L, merged[0].uploadedAt)
    }

    @Test
    fun onlyNewCloudHashIsAdded() {
        val cached = listOf(image(hA, at = 100))
        val cloud = listOf(image(hA, at = 100), file(hB, at = 500))
        val merged = LibraryMerge.merge(cached, cloud)
        assertEquals(2, merged.size)
        assertEquals(setOf(hA, hB), merged.map { it.hash }.toSet())
    }

    @Test
    fun hidingDoesNotDeleteLocalCache() {
        // 隐藏只过滤显示；merged（= 本机缓存 ∪ 云端）仍包含该 hash
        val merged = LibraryMerge.merge(listOf(image(hA, at = 1)), emptyList())
        assertEquals(1, merged.size)
        assertTrue(LibraryUiModel.rows(merged, me, hiddenHashes = setOf(hA)).isEmpty())
        assertTrue("缓存不因隐藏而消失", LibraryUiModel.rows(merged, me).isNotEmpty())
    }

    private fun classBytes(resource: String): String {
        val bytes = javaClass.classLoader!!.getResourceAsStream(resource)?.readBytes() ?: ByteArray(0)
        assertTrue("class 资源应可读: $resource", bytes.isNotEmpty())
        return String(bytes, Charsets.ISO_8859_1)
    }
}
