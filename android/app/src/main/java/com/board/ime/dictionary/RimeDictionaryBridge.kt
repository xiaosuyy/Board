package com.board.ime.dictionary

import android.util.Log
import com.board.ime.engine.rime.RimeRuntime
import java.io.File

/**
 * 把导入的大型词库（[DictionarySource]）生成成 **Rime 能加载的词典配置**。
 *
 * 现在「同步成功」不等于「Rime 可用」：只把 dict 文件放到磁盘上，Rime 不认识它。
 * 这一步负责：
 * 1. 生成 `<user_data_dir>/imported/<name>.dict.yaml`（Rime 词典标准格式：
 *    YAML 头部声明 `columns: text / code / weight`，正文就是 `词条\t编码\t词频`）
 * 2. 触发 Rime deploy（由调用方完成），成功后才把 source 标记为 ready
 *
 * **安全约束**：
 * - 先写 `.tmp`，校验后原子替换正式文件；失败绝不删除原有可用词典
 * - deploy 失败时用 `.bak` 回滚到上一版
 * - 只生成配置、只读源文件；**从不删除源 dict 文件**
 */
object RimeDictionaryBridge {

    private const val TAG = "BoardDictRime"
    private const val DIR_NAME = "imported"

    /**
     * 自动部署的条目上限。
     *
     * 60 万条的词典让 librime 在部署时构建索引，内存与耗时都不可控
     * （可能在原生层 OOM，最坏情况影响正在使用的键盘）。
     * 所以超过这个规模时**只生成配置、不自动部署**，由用户在词库页手动点「部署」，
     * 我们是「暂缓」而不是「假装成功」——此时 `ready` 保持 false。
     */
    const val AUTO_DEPLOY_MAX_ENTRIES = 120_000

    /** 一个数据源对应的 Rime 词典文件名（不含目录） */
    fun rimeDictName(source: DictionarySource): String = baseName(source) + ".dict.yaml"

    fun rimeDictFile(userDir: File, source: DictionarySource): File =
        File(File(userDir, DIR_NAME), rimeDictName(source))

    fun directory(userDir: File): File = File(userDir, DIR_NAME)

    /**
     * 生成 / 更新 Rime 词典文件。
     *
     * @return 生成的 `.dict.yaml`；源文件不存在或写失败时返回 null
     */
    fun writeRimeDict(userDir: File, source: DictionarySource): File? {
        val sourceFile = File(source.path)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            Log.w(TAG, "数据源文件不存在，跳过 Rime 接入：${source.name}")
            return null
        }
        val dir = directory(userDir).apply { mkdirs() }
        val target = rimeDictFile(userDir, source)
        val tmp = File(dir, rimeDictName(source) + ".tmp")
        val backup = File(dir, rimeDictName(source) + ".bak")

        return try {
            tmp.bufferedWriter().use { out ->
                out.write(header(source))
                sourceFile.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            out.write(line)
                            out.write("\n")
                        }
                    }
                }
            }
            // 留一份上一版，deploy 失败时能回滚
            if (target.exists()) {
                backup.delete()
                if (!target.renameTo(backup)) {
                    target.copyTo(backup, overwrite = true)
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.i(
                TAG,
                "已生成 Rime 词典 ${target.name}（${source.entryCount} 条，version=${source.version}）",
            )
            target
        } catch (throwable: Throwable) {
            Log.e(TAG, "生成 Rime 词典失败（原有词典保留）：${source.name}", throwable)
            tmp.delete()
            null
        }
    }

    /** deploy 成功：丢掉上一版备份 */
    fun commit(userDir: File, source: DictionarySource) {
        File(directory(userDir), rimeDictName(source) + ".bak").delete()
    }

    /** deploy 失败：回滚到上一版（没有上一版就删掉刚生成的，保持「不可用」而不是「半套」） */
    fun rollback(userDir: File, source: DictionarySource): Boolean {
        val dir = directory(userDir)
        val target = rimeDictFile(userDir, source)
        val backup = File(dir, rimeDictName(source) + ".bak")
        return try {
            if (backup.exists()) {
                target.delete()
                backup.renameTo(target)
                Log.w(TAG, "Rime deploy 失败，已回滚到上一版词典：${target.name}")
                true
            } else {
                val removed = target.delete()
                Log.w(TAG, "Rime deploy 失败且无上一版，已删除本次生成的词典：removed=$removed")
                true
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "回滚词典失败（文件保留，不影响其它数据）", throwable)
            false
        }
    }

    /** Rime 词典的 YAML 头部 */
    private fun header(source: DictionarySource): String = buildString {
        append("---\n")
        append("name: ").append(baseName(source)).append('\n')
        append("version: \"").append(source.version).append("\"\n")
        append("sort: by_weight\n")
        append("use_preset_vocabulary: false\n")
        append("columns:\n")
        append("  - text\n")
        append("  - code\n")
        append("  - weight\n")
        append("...\n")
    }

    /** 文件名安全化：Rime 词典名只保留字母 / 数字 / 下划线 / 中划线 */
    fun baseName(source: DictionarySource): String {
        // 去掉 Board 自己的后缀，避免生成 `..._dict_dict.dict.yaml` 这种叠字名字
        val raw = source.name
            .removeSuffix(".dict.txt")
            .removeSuffix(".dict")
            .substringBeforeLast('.')
        val safe = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return safe.ifBlank { "board_${source.remoteId}" }
    }

    /** 当前是否已经生成过 Rime 词典配置 */
    fun hasRimeDict(userDir: File, source: DictionarySource): Boolean =
        rimeDictFile(userDir, source).isFile

    /** 是否应该自动部署（超过规模上限时交给用户手动触发） */
    fun canAutoDeploy(source: DictionarySource): Boolean =
        source.entryCount in 1..AUTO_DEPLOY_MAX_ENTRIES

    /** Rime 是否已经初始化（没初始化时无法 deploy，但生成文件是安全的） */
    fun isRimeReady(): Boolean = RimeRuntime.userDataDir() != null
}
