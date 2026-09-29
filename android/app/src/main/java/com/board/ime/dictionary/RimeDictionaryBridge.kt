package com.board.ime.dictionary

import android.util.Log
import com.board.ime.engine.rime.RimeRuntime
import java.io.File

/**
 * 大型词库与 Rime 之间的桥。
 *
 * ## 分工
 * - **长期存储**：`imported/<sha256>.dict.yaml.gz`（见 [DictionaryStore]）
 * - **部署**：解压成 `<user_dir>/imported/<名字>.dict.yaml` → 触发 Rime deploy
 *   → 成功即删掉这份临时的 `.dict.yaml`，只留 gz
 *
 * ## 为什么部署要临时解压
 * Rime 部署时会读 `.dict.yaml` 编译出 `table.bin` / `prism.bin`；
 * **输入过程只读编译产物**，跟 `.dict.yaml`、`.gz` 都没关系。
 * 所以「长期压缩 + 部署时临时解压」不会给打字路径增加任何解压开销。
 *
 * ## 安全
 * - 解压出的临时文件在部署成功/失败后都会清理
 * - gz 永不被删除（除非数据源本身被移除）
 * - deploy 失败时不会留下半份词典
 */
object RimeDictionaryBridge {

    private const val TAG = "BoardDictRime"
    private const val DIR_NAME = "imported"

    /**
     * 自动部署的条目上限。
     *
     * 60 万条的词典让 librime 在部署时构建索引，内存与耗时都不可控，
     * 超过这个规模就只生成配置、由用户手动触发部署（`ready` 保持 false）。
     */
    const val AUTO_DEPLOY_MAX_ENTRIES = 120_000

    fun directory(userDir: File): File = File(userDir, DIR_NAME)

    /** Rime 词典名（稳定：按数据源名字派生，不含 hash，避免方案引用漂移） */
    fun rimeDictName(source: DictionarySource): String = baseName(source) + ".dict.yaml"

    fun rimeDictFile(userDir: File, source: DictionarySource): File =
        File(directory(userDir), rimeDictName(source))

    /** 当前是否已经有解压出来的、可供 Rime 部署的工作文件 */
    fun isStaged(userDir: File, source: DictionarySource): Boolean =
        rimeDictFile(userDir, source).isFile

    /**
     * 部署前把 gz 解压成 Rime 能读的工作文件。
     *
     * @param importedDir 长期存储目录（`files/rime/user/imported`）
     * @return 解压出的 `.dict.yaml`；失败返回 null（此时**不会**动 gz）
     */
    fun stageForDeploy(userDir: File, source: DictionarySource, importedDir: File): File? {
        val stored = File(source.path)
        if (!stored.isFile) {
            Log.w(TAG, "数据源文件不存在：${stored.absolutePath}")
            return null
        }
        val target = rimeDictFile(userDir, source)
        if (!DictionaryStore.isCompressedStore(stored)) {
            // 兼容：还没迁移成 gz 的旧文件，直接复制过去（迁移会在启动时统一处理）
            return try {
                target.parentFile?.mkdirs()
                stored.copyTo(target, overwrite = true)
                target
            } catch (throwable: Throwable) {
                Log.e(TAG, "复制词典失败：${throwable.message}", throwable)
                null
            }
        }
        val ok = DictionaryStore.decompressTo(stored, target, source.hash)
        if (!ok) {
            Log.w(TAG, "解压失败，保持 gz 不动：${stored.name}")
            return null
        }
        Log.i(TAG, "已解压待部署：${target.name}（${target.length()} B）")
        return target
    }

    /** 部署结束后清理临时解压出来的工作文件（**gz 保留**） */
    fun removeStaged(userDir: File, source: DictionarySource): Boolean {
        val target = rimeDictFile(userDir, source)
        val removed = runCatching { !target.exists() || target.delete() }.getOrDefault(false)
        if (removed && target.exists()) {
            Log.w(TAG, "临时词典删除失败：${target.name}")
        }
        return removed
    }

    /** 磁盘上是否还留着旧的（未压缩）Rime 词典文件 */
    fun legacyFile(userDir: File, source: DictionarySource): File = rimeDictFile(userDir, source)

    /** 是否应该自动部署（超过规模上限时交给用户手动触发） */
    fun canAutoDeploy(source: DictionarySource): Boolean =
        source.entryCount in 1..AUTO_DEPLOY_MAX_ENTRIES

    /** Rime 是否已经初始化（没初始化时无法 deploy） */
    fun isRimeReady(): Boolean = RimeRuntime.userDataDir() != null

    /** 文件名安全化：Rime 词典名只保留字母 / 数字 / 下划线 / 中划线 */
    fun baseName(source: DictionarySource): String {
        // 去掉 Board 自己的后缀，避免生成 `..._dict_dict.dict.yaml` 这种叠字名字
        val raw = source.name
            .removeSuffix(".dict.txt")
            .removeSuffix(".dict.yaml")
            .removeSuffix(".dict")
            .substringBeforeLast('.')
        val safe = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return safe.ifBlank { "board_${source.remoteId}" }
    }
}
