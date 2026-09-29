package com.board.ime.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.board.ime.R
import com.board.ime.database.BoardDatabase
import com.board.ime.database.entity.DictionaryEntry
import com.board.ime.dictionary.DictionaryRepository
import com.board.ime.dictionary.WordType
import com.board.ime.databinding.ActivityDictionaryBinding
import com.board.ime.databinding.DialogDictionaryEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的词库。
 *
 * 一份列表承载四类词：学习词（librime userdb 的只读镜像）、自定义词、固定词、屏蔽词。
 * 自定义词 / 固定词保存后会同步进 Rime 的 custom_phrase，输入时优先出现。
 */
class DictionaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDictionaryBinding
    private lateinit var repository: DictionaryRepository

    private var filter: WordType? = null
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = DictionaryRepository(BoardDatabase.get(this).dictionaryDao(), this)

        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonAdd.setOnClickListener { showEditor(null) }
        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.filter_learned -> WordType.LEARNED
                R.id.filter_custom -> WordType.CUSTOM
                R.id.filter_pinned -> WordType.PINNED
                R.id.filter_blocked -> WordType.BLOCKED
                else -> null
            }
            startObserving()
        }
        binding.filterAll.isChecked = true
    }

    override fun onResume() {
        super.onResume()
        // 进入页面时把 librime 的学习记录镜像过来（本地导出，不联网）
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repository.refreshLearnedFromRime() } }
            startObserving()
        }
    }

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            val flow = filter?.let { repository.observeByType(it) } ?: repository.observeAll()
            flow.collect { render(it) }
        }
    }

    // ---------------------------------------------------------------- 列表

    private fun render(entries: List<DictionaryEntry>) {
        binding.listContainer.removeAllViews()
        binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.listScroll.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        entries.forEach { entry -> binding.listContainer.addView(createRow(entry)) }
    }

    private fun createRow(entry: DictionaryEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(6f), dp(10f))
            background = roundedBackground(
                ContextCompat.getColor(this@DictionaryActivity, R.color.app_surface),
                dp(10f).toFloat(),
                ContextCompat.getColor(this@DictionaryActivity, R.color.app_outline),
            )
            isClickable = true
            setOnClickListener { showEditor(entry) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6f) }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(this).apply {
                text = entry.word
                setTextColor(ContextCompat.getColor(this@DictionaryActivity, R.color.app_on_surface))
                textSize = 16f
            },
        )
        val detail = buildString {
            append(entry.type.label)
            entry.code?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            if (entry.frequency > 0) append(" · 词频 ").append(entry.frequency)
            if (!entry.enabled) append(" · 已停用")
        }
        texts.addView(
            TextView(this).apply {
                text = detail
                setTextColor(
                    ContextCompat.getColor(this@DictionaryActivity, R.color.app_on_surface_variant),
                )
                textSize = 12f
            },
        )
        row.addView(texts)

        row.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_board_close)
                setColorFilter(
                    ContextCompat.getColor(this@DictionaryActivity, R.color.app_on_surface_variant),
                )
                background = null
                contentDescription = getString(R.string.common_confirm)
                setOnClickListener { confirmDelete(entry) }
                layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            },
        )
        return row
    }

    // ---------------------------------------------------------------- 编辑

    private fun showEditor(entry: DictionaryEntry?) {
        val dialogBinding = DialogDictionaryEditBinding.inflate(layoutInflater)
        val readOnly = entry?.type == WordType.LEARNED

        dialogBinding.editWord.setText(entry?.word.orEmpty())
        dialogBinding.editCode.setText(entry?.code.orEmpty())
        dialogBinding.editFrequency.setText((entry?.frequency ?: 0).toString())
        dialogBinding.switchEnabled.isChecked = entry?.enabled ?: true
        dialogBinding.typeGroup.check(
            when (entry?.type) {
                WordType.LEARNED -> R.id.type_learned
                WordType.PINNED -> R.id.type_pinned
                WordType.BLOCKED -> R.id.type_blocked
                else -> R.id.type_custom
            },
        )

        if (readOnly) {
            // 学习词是 librime 的镜像，不允许在这里改
            dialogBinding.editWord.isEnabled = false
            dialogBinding.editCode.isEnabled = false
            dialogBinding.editFrequency.isEnabled = false
            dialogBinding.typeGroup.isEnabled = false
            dialogBinding.switchEnabled.isEnabled = false
            dialogBinding.editWord.setInputType(InputType.TYPE_NULL)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (entry == null) R.string.dictionary_add_title else R.string.dictionary_edit_title,
            )
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_save, null)
            .create()

        if (readOnly) {
            dialog.setMessage(getString(R.string.dictionary_type_locked_hint))
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (readOnly) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                saveEntry(entry, dialogBinding, dialog)
            }
        }
        dialog.show()
    }

    private fun saveEntry(
        existing: DictionaryEntry?,
        dialogBinding: DialogDictionaryEditBinding,
        dialog: androidx.appcompat.app.AlertDialog,
    ) {
        val word = dialogBinding.editWord.text?.toString().orEmpty().trim()
        if (word.isEmpty()) {
            toast(getString(R.string.dictionary_word_required))
            return
        }
        val code = dialogBinding.editCode.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val frequency = dialogBinding.editFrequency.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val type = when (dialogBinding.typeGroup.checkedButtonId) {
            R.id.type_learned -> WordType.LEARNED
            R.id.type_pinned -> WordType.PINNED
            R.id.type_blocked -> WordType.BLOCKED
            else -> WordType.CUSTOM
        }
        val enabled = dialogBinding.switchEnabled.isChecked

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (existing == null) {
                    repository.addWord(word, code, type, frequency)
                } else {
                    repository.updateWord(existing.id, word, code, frequency, type, enabled)
                }
            }
            if (!ok) {
                toast(getString(R.string.dictionary_word_exists))
                return@launch
            }
            withContext(Dispatchers.IO) { repository.syncCustomPhrasesToRime() }
            dialog.dismiss()
            toast(getString(R.string.dictionary_saved))
        }
    }

    private fun confirmDelete(entry: DictionaryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dictionary_delete_title, entry.word))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repository.delete(entry)
                        repository.syncCustomPhrasesToRime()
                    }
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- 小工具

    private fun roundedBackground(color: Int, radius: Float, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1f), strokeColor)
        }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }
}
