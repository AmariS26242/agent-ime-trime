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
 *
 * The panel also hosts the backend settings (endpoint / token). Because the
 * panel has no input connection of its own, its "input fields" are selectable
 * TextViews: tap one, then use the same keyboard to type into it.
 */
class AgentPanel(
    host: InputView,
    private val scope: ThemeScope,
) {
    private val context = host.themedContext
    private val density = context.resources.displayMetrics.density

    private enum class Field { QUESTION, ENDPOINT, TOKEN }

    private val question = StringBuilder()
    private val endpointBuf = StringBuilder(AgentConfig.endpoint())
    private val tokenBuf = StringBuilder(AgentConfig.token())
    private var field = Field.QUESTION
    private var settingsOpen = false
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

    /** 保存后端设置（地址, 令牌）。 */
    var onSaveSettings: ((String, String) -> Unit)? = null

    /** 敏感输入域里面板只显示说明，不接受输入、不允许写入。 */
    private var blocked = false

    private val questionView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                field = Field.QUESTION
                render()
            }
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

    // ---- 设置区（后端地址 / 令牌） ----

    private val settingsLabelStyle: TextView.() -> Unit = {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(subTextColor)
        setPadding(dp(10), dp(4), dp(10), 0)
    }

    private val endpointLabel = TextView(context).apply { text = "后端地址（点下面这一行选中，再用键盘输入 / 退格）"; settingsLabelStyle() }
    private val endpointView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                field = Field.ENDPOINT
                render()
            }
        }
    private val tokenLabel = TextView(context).apply { text = "客户端令牌"; settingsLabelStyle() }
    private val tokenView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                field = Field.TOKEN
                render()
            }
        }
    private val settingsHint =
        TextView(context).apply {
            text = "地址形如 http://电脑IP:8787/v1/chat/stream；手机与电脑需在同一 Wi-Fi。改完按 Enter 或点「保存」。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(subTextColor)
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
    private val saveButton = panelButton("保存")
    private val backButton = panelButton("返回问答")

    private val settingsSection =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(endpointLabel, matchWidth(wrapContent()))
            addView(endpointView, matchWidth(wrapContent()))
            addView(tokenLabel, matchWidth(wrapContent()))
            addView(tokenView, matchWidth(wrapContent()))
            addView(settingsHint, matchWidth(wrapContent()))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addButton(saveButton)
                    addButton(backButton)
                },
                matchWidth(wrapContent()),
            )
            visibility = View.GONE
        }

    private val buttonRowView =
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

    val root: View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(8), dp(4), dp(8), dp(6))
            addView(buildHeader(), matchWidth(wrapContent()))
            addView(settingsSection, matchWidth(wrapContent()))
            addView(questionView, matchWidth(wrapContent()))
            addView(
                answerScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)),
            )
            addView(statusView, matchWidth(wrapContent()))
            addView(buttonRowView, matchWidth(wrapContent()))
            visibility = View.GONE
        }

    init {
        sendButton.setOnClickListener {
            android.util.Log.i("AgentDbg", "click send q=$question")
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
        saveButton.setOnClickListener { saveSettings() }
        backButton.setOnClickListener { closeSettings() }
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
                    text = "设置"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setTextColor(subTextColor)
                    setOnClickListener { toggleSettings() }
                },
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
        // 问答区
        questionView.text = question.toString().ifEmpty { "用键盘输入问题…" }
        questionView.setTextColor(if (question.isEmpty()) subTextColor else textColor)
        answerView.text = answer
        answerView.setTextColor(textColor)
        statusView.text = status
        statusView.setTextColor(subTextColor)
        cancelButton.visibility = if (streaming) View.VISIBLE else View.GONE
        sendButton.isEnabled = !streaming && !blocked
        sendButton.alpha = if (streaming) 0.4f else 1f

        // 设置区
        val settingsVisibility = if (settingsOpen) View.VISIBLE else View.GONE
        settingsSection.visibility = settingsVisibility
        questionView.visibility = if (settingsOpen) View.GONE else View.VISIBLE
        answerScroll.visibility = if (settingsOpen) View.GONE else View.VISIBLE
        buttonRowView.visibility = if (settingsOpen) View.GONE else View.VISIBLE
        if (settingsOpen) {
            endpointView.text = endpointBuf.toString().ifEmpty { "（空，使用默认）" }
            tokenView.text = tokenBuf.toString().ifEmpty { "（空，使用默认）" }
            endpointView.setTextColor(if (endpointBuf.isEmpty()) subTextColor else textColor)
            tokenView.setTextColor(if (tokenBuf.isEmpty()) subTextColor else textColor)
            endpointView.background = fieldBackground(selected = field == Field.ENDPOINT)
            tokenView.background = fieldBackground(selected = field == Field.TOKEN)
        }
    }

    private fun fieldBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(colorOf({ scope.colors.candidateBackground }, 0xFF23262B.toInt()))
            setStroke(dp(if (selected) 2 else 1), if (selected) highlightColor else colorOf({ scope.colors.borderColor }, 0xFF3A3F46.toInt()))
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

    // ---- 设置 ----

    fun toggleSettings() {
        if (settingsOpen) {
            closeSettings()
        } else {
            openSettings()
        }
    }

    fun openSettings() {
        settingsOpen = true
        field = Field.ENDPOINT
        endpointBuf.setLength(0)
        endpointBuf.append(AgentConfig.endpoint())
        tokenBuf.setLength(0)
        tokenBuf.append(AgentConfig.token())
        status = ""
        render()
    }

    fun closeSettings() {
        settingsOpen = false
        field = Field.QUESTION
        render()
    }

    val isSettingsOpen: Boolean
        get() = settingsOpen

    private fun saveSettings() {
        val endpoint = endpointBuf.toString().trim()
        val token = tokenBuf.toString().trim()
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            setStatus("地址要以 http:// 或 https:// 开头")
            return
        }
        onSaveSettings?.invoke(endpoint, token)
        settingsOpen = false
        field = Field.QUESTION
        status = "已保存后端设置"
        render()
    }

    // ---- 面板输入 ----

    fun appendText(text: String) {
        if (blocked) return
        when {
            settingsOpen && field == Field.ENDPOINT -> {
                endpointBuf.append(text)
                render()
            }
            settingsOpen && field == Field.TOKEN -> {
                tokenBuf.append(text)
                render()
            }
            else -> {
                question.append(text)
                status = ""
                render()
            }
        }
    }

    fun backspace() {
        when {
            settingsOpen && field == Field.ENDPOINT -> {
                if (endpointBuf.isNotEmpty()) endpointBuf.setLength(endpointBuf.length - 1)
                render()
            }
            settingsOpen && field == Field.TOKEN -> {
                if (tokenBuf.isNotEmpty()) tokenBuf.setLength(tokenBuf.length - 1)
                render()
            }
            question.isNotEmpty() -> {
                question.setLength(question.length - 1)
                render()
            }
        }
    }

    /** Enter / 「发送」在设置态等价于保存。 */
    fun submit() {
        if (settingsOpen) {
            saveSettings()
        } else {
            val text = question.toString().trim()
            if (text.isEmpty()) setStatus("先输入一个问题，再发送") else onSend?.invoke(text)
        }
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

    fun currentEndpoint(): String = AgentConfig.endpoint()

    private fun panelBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            // 强制不透明：面板下方是 trime 的 preedit/候选视图，半透明会把它们的边框透出来
            val base = colorOf({ scope.colors.candidateBackground }, 0xFF23262B.toInt())
            setColor(android.graphics.Color.rgb(
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base),
            ))
            setStroke(dp(1), colorOf({ scope.colors.borderColor }, 0xFF3A3F46.toInt()))
        }

    private fun colorOf(get: () -> Int, fallback: Int): Int =
        runCatching { get() }.getOrDefault(fallback)

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun wrapContent(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun matchWidth(height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
}
