package com.board.ime.engine.rime

import android.content.Context
import java.io.File

/**
 * 把打包在 assets/rime 里的标准 Rime 数据释放到应用私有目录。
 *
 * 目录约定（与 Rime 官方一致）：
 * - shared：随包发布的方案 / 词库 / 配置 / OpenCC 数据（只读语义）
 * - user  ：用户数据与部署产物 build/（librime 可写）
 *
 * 只有 assets 版本号变化时才重新复制，避免每次启动都做无谓 IO。
 */
class RimeDataInstaller(private val context: Context) {

    fun sharedDir(): File = File(context.filesDir, "$ROOT_DIR/shared").apply { mkdirs() }

    fun userDir(): File = File(context.filesDir, "$ROOT_DIR/user").apply { mkdirs() }

    fun logDir(): File = File(context.cacheDir, "$ROOT_DIR/log").apply { mkdirs() }

    /** @return true 表示本次重新复制过数据（需要重新部署） */
    fun install(): Boolean {
        val target = sharedDir()
        val marker = File(target, MARKER_NAME)
        if (marker.isFile && marker.readText().trim() == ASSET_VERSION) {
            return false
        }

        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()
        copyAsset(ASSET_DIR, target)
        marker.writeText(ASSET_VERSION)
        return true
    }

    private fun copyAsset(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // assets.list 返回空数组表示这是一个文件
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAsset("$assetPath/$child", File(target, child))
        }
    }

    companion object {
        private const val ROOT_DIR = "rime"
        private const val ASSET_DIR = "rime"
        private const val MARKER_NAME = ".asset_version"

        /**
         * assets 数据版本。升到 v2 时把数字加一，用户下次启动会自动重新复制并部署。
         * v1：朙月拼音 + 简体（luna_pinyin_simp）+ OpenCC t2s + stroke 依赖
         * v2：加入 luna_pinyin_simp.custom.yaml（menu/page_size = 9，方便候选横向滑动）
         * v3：候选对齐微信本地行为 —— 模糊音 + 纠错、前缀补全、一页 50 个候选
         * v4：用户词优先 —— custom_phrase 的 initial_quality 提到基础词频之上
         * v5：新增 board_common 现代常用词补充词源（essay.txt 高频 2–4 字词，自动注音）
         * v6：board_common 加占位方案 + schema/dependencies，deployer 才会编译出 table.bin；
         *     英文候选改为按词频排序的 10k 词表（assets/english_words.txt）
         * v7：board_common 追加常用语气词 / 叠词的“口语编码”（如 嗯=e、嗯嗯=e e），
         *     对齐微信：输入 ee → 嗯嗯 排第一
         * v8：开启整句候选（xiangquzijieshangban → 想去字节上班）
         * v9：board_common 补 字节 / GitHub；候选按「整句 → 词 → 单字」重排
         * v10：7 种双拼方案（自然码 / 小鹤 / 微软 / 搜狗 / 智能ABC / 紫光 / 拼音加加）；
         *      模糊音 / 繁体改由运行期影子 schema 注入（12 组单独开关）
         */
        const val ASSET_VERSION = "10"
    }
}
