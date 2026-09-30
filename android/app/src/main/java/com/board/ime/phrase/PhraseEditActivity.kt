package com.board.ime.phrase

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.board.ime.database.BoardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 常用语的轻量编辑界面（新增 / 编辑共用）。
 *
 * 为什么用 Activity 而不是面板内的输入框：输入法**无法用自己弹出的键盘**
 * 给自己内部的 View 输入文字（键盘的按键只会送到当前编辑框），
 * 所以用一个极简 Activity 承载真正的 EditText，新增/编辑都走它。
 *
 * 保存后直接写 Room；常用语面板通过 Flow 自动刷新，不需要手动通知。
 */
class PhraseEditActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phraseId = intent.getStringExtra(EXTRA_PHRASE_ID)
        val existingText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val repository = PhraseRepository(BoardDatabase.get(this).phraseDao())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        root.addView(
            TextView(this).apply {
                text = if (phraseId == null) "新增常用语" else "编辑常用语"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            },
        )

        val editor = EditText(this).apply {
            setText(existingText)
            hint = "输入常用语……"
            gravity = Gravity.TOP or Gravity.START
            setLines(5)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(
            editor,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = dp(12) },
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            Button(this).apply {
                text = "取消"
                setOnClickListener { finish() }
            },
        )
        buttons.addView(
            Button(this).apply {
                text = "保存"
                setOnClickListener {
                    val text = editor.text?.toString().orEmpty()
                    if (text.trim().isEmpty()) {
                        Toast.makeText(this@PhraseEditActivity, "常用语不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    scope.launch {
                        if (phraseId == null) {
                            repository.add(text)
                        } else {
                            // 编辑：保留 phraseId 与排序位置，只更新正文与 updatedAt
                            repository.updateText(phraseId, text)
                        }
                        withContext(Dispatchers.Main) { finish() }
                    }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        setContentView(root)
        editor.requestFocus()
        editor.setSelection(editor.text?.length ?: 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PHRASE_ID = "phrase_id"
        private const val EXTRA_TEXT = "phrase_text"

        /** 新增 */
        fun addIntent(context: Context): Intent =
            Intent(context, PhraseEditActivity::class.java)

        /** 编辑已有条目 */
        fun editIntent(context: Context, phraseId: String, text: String): Intent =
            Intent(context, PhraseEditActivity::class.java)
                .putExtra(EXTRA_PHRASE_ID, phraseId)
                .putExtra(EXTRA_TEXT, text)
    }
}
