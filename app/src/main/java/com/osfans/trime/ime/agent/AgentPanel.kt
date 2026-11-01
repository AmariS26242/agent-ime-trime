/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.agent

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.InputView

/**
 * Floating Agent panel shown above the keyboard.
 *
 * The panel deliberately keeps the keyboard visible: text the user types is
 * composed by rime as usual, but committed characters are routed into
 * [question] by [com.osfans.trime.ime.core.TrimeInputMethodService] through
 * `panelTextSink` instead of being written to the host editor. Only
 * [insertAnswer] hands text back to the host input connection.
 */
class AgentPanel(
    host: InputView,
    private val scope: ThemeScope,
) {
    private val context = host.themedContext
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private val question = StringBuilder()
    private var answer: String = ""
    private var status: String = ""

    private val textColor = colorOf({ scope.colors.keyTextColor }, 0xFFEEEEEE.toInt())
    private val subTextColor = colorOf({ scope.colors.commentTextColor }, 0xFF9A9A9A.toInt())
    private val highlightColor = colorOf({ scope.colors.hilitedCandidateTextColor }, 0xFF4C9AFF.toInt())

    var onSend: ((String) -> Unit)? = null
    var onInsert: ((String) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    /** 敏感输入域里面板只显示说明，不接受输入、不允许写入。 */
    private var blocked = false

    private val questionView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

    private val answerView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextIsSelectable(true)
        }

    private val statusView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }

    private val sendButton = panelButton("发送")
    private val insertButton = panelButton("插入")
    private val undoButton = panelButton("撤销")
    private val clearButton = panelButton("清空")
    private val cancelButton = panelButton("取消")

    /** 流式生成中：发送禁用，出现「取消」。 */
    private var streaming = false

    private val answerScroll =
        ScrollView(context).apply {
            isFillViewport = true
            addView(
                answerView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    val root: View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(8), dp(4), dp(8), dp(6))
            addView(buildHeader(), matchWidth(wrapContent()))
            addView(questionView, matchWidth(wrapContent()))
            addView(
                answerScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)),
            )
            addView(statusView, matchWidth(wrapContent()))
            addView(buildButtonRow(), matchWidth(wrapContent()))
            visibility = View.GONE
        }

    init {
        root.setOnTouchListener { _, event ->
            android.util.Log.i("AgentDbg", "panelTouch action=${event.action} x=${event.x} y=${event.y}")
            false
        }
        sendButton.setOnClickListener {
            android.util.Log.i("AgentDbg", "click send q=${question}")
            val text = question.toString().trim()
            if (text.isEmpty()) {
                setStatus("先输入一个问题，再发送")
            } else {
                onSend?.invoke(text)
            }
        }
        insertButton.setOnClickListener {
            android.util.Log.i("AgentDbg", "click insert answer=$answer")
            val text = answer
            if (text.isEmpty()) {
                setStatus("还没有回答可插入")
            } else {
                onInsert?.invoke(text)
            }
        }
        clearButton.setOnClickListener {
            android.util.Log.i("AgentDbg", "click clear")
            // 生成中清空 = 先取消请求，避免回答继续写进已清空的面板
            if (streaming) onCancel?.invoke()
            question.setLength(0)
            answer = ""
            status = ""
            render()
        }
        undoButton.setOnClickListener {
            android.util.Log.i("AgentDbg", "click undo")
            onUndo?.invoke()
        }
        cancelButton.setOnClickListener {
            android.util.Log.i("AgentDbg", "click cancel")
            onCancel?.invoke()
        }
        refreshColors()
        render()
    }

    private fun buildHeader(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(6), dp(2))
            addView(
                TextView(context).apply {
                    text = "AI 助手"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(highlightColor)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(context).apply {
                    text = "关闭"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setTextColor(subTextColor)
                    setOnClickListener { onClose?.invoke() }
                },
            )
        }

    private fun buildButtonRow(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
            addButton(sendButton)
            addButton(insertButton)
            addButton(undoButton)
            addButton(clearButton)
            addButton(cancelButton)
        }

    private fun LinearLayout.addButton(button: TextView) {
        addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
    }

    private fun panelButton(label: String): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
        }

    private fun render() {
        questionView.text = question.toString().ifEmpty { "用键盘输入问题…" }
        questionView.setTextColor(if (question.isEmpty()) subTextColor else textColor)
        answerView.text = answer
        answerView.setTextColor(textColor)
        statusView.text = status
        statusView.setTextColor(subTextColor)
        cancelButton.visibility = if (streaming) View.VISIBLE else View.GONE
        sendButton.isEnabled = !streaming && !blocked
        sendButton.alpha = if (streaming) 0.4f else 1f
    }

    fun toggle() {
        root.visibility = if (root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    val isOpen: Boolean
        get() = root.visibility == View.VISIBLE

    fun hide() {
        root.visibility = View.GONE
    }

    fun refreshColors() {
        root.background = panelBackground()
        render()
    }

    fun appendText(text: String) {
        if (blocked) return
        question.append(text)
        status = ""
        render()
    }

    /** 敏感输入域：面板只展示原因，按钮全部禁用，不接受键盘提交。 */
    fun enterBlockedMode(message: String) {
        blocked = true
        question.setLength(0)
        answer = ""
        status = message
        listOf(sendButton, insertButton, undoButton).forEach {
            it.isEnabled = false
            it.alpha = 0.4f
        }
        render()
    }

    fun exitBlockedMode() {
        blocked = false
        listOf(sendButton, insertButton, undoButton).forEach {
            it.isEnabled = true
            it.alpha = 1f
        }
    }

    val isBlocked: Boolean
        get() = blocked

    /** 宿主有选区时「插入」改称「改写」，语义是替换选区文本。 */
    fun setInsertLabel(label: String) {
        insertButton.text = label
    }

    fun backspace() {
        if (question.isNotEmpty()) {
            question.setLength(question.length - 1)
            render()
        }
    }

    fun setAnswer(text: String) {
        answer = text
        status = ""
        render()
    }

    fun setStatus(text: String) {
        status = text
        render()
    }

    /** 发起流式请求：清空旧回答，禁用发送，显示「取消」。 */
    fun beginStreaming(statusText: String) {
        streaming = true
        answer = ""
        status = statusText
        render()
    }

    /** 收到一段增量文本：追加显示并滚到底部。 */
    fun appendAnswer(piece: String) {
        if (piece.isEmpty()) return
        answer += piece
        answerView.text = answer
        status = "生成中…"
        statusView.text = status
        answerScroll.post { answerScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun endStreaming(statusText: String) {
        streaming = false
        status = statusText
        render()
    }

    val isStreaming: Boolean
        get() = streaming

    fun questionText(): String = question.toString()

    fun answerText(): String = answer

    private fun panelBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(colorOf({ scope.colors.candidateBackground }, 0xFF23262B.toInt()))
            setStroke(dp(1), colorOf({ scope.colors.borderColor }, 0xFF3A3F46.toInt()))
        }

    private fun colorOf(get: () -> Int, fallback: Int): Int =
        runCatching { get() }.getOrDefault(fallback)

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun wrapContent(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun matchWidth(height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
}
