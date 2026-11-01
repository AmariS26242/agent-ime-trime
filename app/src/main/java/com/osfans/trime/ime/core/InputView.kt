/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.agent.AgentPanel
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.allInstances
import org.kodein.di.bindInstance
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope),
    DIAware {
    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    override val di = DI {
        bindInstance<InputView> { this@InputView }
        bindInstance<ContextThemeWrapper> { themedContext }
        bindInstance<ThemeScope> { scope }
        bindInstance<Theme> { scope.theme }
        bindInstance<TrimeInputMethodService> { service }
        bindInstance<RimeSession> { rime }
        bindSingleton { InputBroadcaster() }
        bindSingleton { PopupDelegate(di) }
        bindSingleton { EnterKeyDisplayDelegate(di) }
        bindSingleton { PreeditDelegate(di) }
        bindSingleton { CommonKeyboardActionListener(di) }
        bindSingleton { BoardWindowManager(di) }
        bindSingleton { InputBarDelegate(di) }
        bindSingleton { CompactCandidateDelegate(di) }
        bindSingleton { KeyboardWindow(di) }
        bindSingleton { LiquidWindow(di) }
    }

    private val broadcaster: InputBroadcaster by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val preedit: PreeditDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    /** Agent panel shown above the keyboard, see [showAgentPanel]. */
    private val agentPanel = AgentPanel(this, scope)
    private val agentClient = com.osfans.trime.ime.agent.AgentClient()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.style.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.style.keyboardPaddingLand
    private val keyboardBottomPadding = theme.style.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.style.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    val keyboardView: View

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")
        popup.refreshColors()
        keyboardWindow.refreshColors()
        inputBar.refreshColors()
        preedit.refreshColors()
        windowManager.refreshColors()
    }

    init {
        // MUST call before any operation
        val receivers: List<InputBroadcastReceiver> by allInstances()
        receivers.forEach { broadcaster.addReceiver(it) }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")

        keyboardView =
            constraintLayout {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    windowManager.view.updateLayoutParams {
                        height = it
                    }
                }
            }

        updateKeyboardSize()

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        add(
            keyboardView,
            lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            },
        )

        add(
            agentPanel.root,
            lParams(matchParent, wrapContent) {
                above(keyboardView)
                startOfParent()
                endOfParent()
            },
        )

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )

        agentPanel.onSend = { submitAgentQuestion() }
        agentPanel.onInsert = { insertAgentAnswer(it) }
        agentPanel.onUndo = { undoAgentAnswer() }
        agentPanel.onClose = { hideAgentPanel() }
        agentPanel.onCancel = { cancelAgentRequest() }
        agentPanel.onSaveSettings = { endpoint, token ->
            com.osfans.trime.ime.agent.AgentConfig.init(themedContext)
            com.osfans.trime.ime.agent.AgentConfig.save(endpoint, token)
            android.util.Log.i("AgentDbg", "settings saved endpoint=$endpoint")
        }
        com.osfans.trime.ime.agent.AgentConfig.init(themedContext)
        agentPanel.root.post {
            val r = android.graphics.Rect()
            agentPanel.root.getGlobalVisibleRect(r)
            android.util.Log.i(
                "AgentDbg",
                "panelRect=$r h=${agentPanel.root.height} w=${agentPanel.root.width} " +
                    "vis=${agentPanel.root.visibility} inputViewH=$height " +
                    "popupVis=${popup.root.visibility} keyboardH=${keyboardView.height}",
            )
        }
    }

    /** Opens the Agent panel and routes committed text into it instead of the host editor. */
    fun showAgentPanel() {
        agentPanel.root.visibility = View.VISIBLE
        agentPanel.root.bringToFront()
        agentPanel.root.post {
            val r = android.graphics.Rect()
            agentPanel.root.getGlobalVisibleRect(r)
            android.util.Log.i("AgentDbg", "shownRect=$r h=${agentPanel.root.height} inputViewH=$height")
        }
        if (service.isSensitiveInput) {
            // 敏感输入域：不接管键盘提交，也不允许写回宿主
            agentPanel.enterBlockedMode("敏感输入：已禁用 AI 面板（不读取、不写入该输入框）")
            service.panelTextSink = null
            service.panelBackspaceSink = null
            service.panelSubmitSink = null
        } else {
            agentPanel.exitBlockedMode()
            agentPanel.setStatus("用键盘输入问题，回车或点「发送」")
            agentPanel.setInsertLabel(if (service.hasHostSelection()) "改写" else "插入")
            service.panelTextSink = agentPanel::appendText
            service.panelBackspaceSink = agentPanel::backspace
            service.panelSubmitSink = { agentPanel.submit() }
        }
        service.onAgentPanelVisibilityChanged()
    }

    /** 面板是否处于「设置」态（此时键盘输入按原样 ASCII 进面板，不走 rime 组合）。 */
    fun isPanelSettingsOpen(): Boolean = agentPanel.isOpen && agentPanel.isSettingsOpen

    /** 面板顶部在窗口中的 Y 坐标（面板不可见时返回 null）。 */    fun agentPanelTopInWindow(): Int? {
        if (agentPanel.root.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        agentPanel.root.getLocationInWindow(loc)
        return loc[1]
    }

    fun hideAgentPanel() {
        agentClient.cancel()
        agentPanel.hide()
        service.panelTextSink = null
        service.panelBackspaceSink = null
        service.panelSubmitSink = null
        service.onAgentPanelVisibilityChanged()
    }

    fun toggleAgentPanel() {
        if (agentPanel.isOpen) {
            hideAgentPanel()
        } else {
            service.captureHostSelection()
            showAgentPanel()
        }
    }

    private fun submitAgentQuestion() {
        val question = agentPanel.questionText().trim()
        if (question.isEmpty()) {
            agentPanel.setStatus("先输入一个问题，再发送")
            return
        }
        agentPanel.beginStreaming("正在请求…")
        agentClient.ask(
            question = question,
            onDelta = { piece -> agentPanel.appendAnswer(piece) },
            onDone = {
                agentPanel.endStreaming(
                    if (agentPanel.answerText().isBlank()) {
                        "模型没有返回内容"
                    } else {
                        "已生成，可点「插入」写入输入框"
                    },
                )
            },
            onError = { message ->
                agentPanel.endStreaming("失败：$message")
            },
        )
    }

    private fun cancelAgentRequest() {
        if (!agentPanel.isStreaming) return
        agentClient.cancel()
        agentPanel.endStreaming("已取消")
    }

    private fun insertAgentAnswer(text: String) {
        val rewrite = service.hasHostSelection()
        service.writeToHost(text)
        agentPanel.setStatus(if (rewrite) "已改写选区（可点「撤销」还原原文）" else "已插入到输入框")
        agentPanel.setInsertLabel("插入")
    }

    private fun undoAgentAnswer() {
        service.undoAgentInsert()
        agentPanel.setStatus("已撤销上一次写入")
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        android.util.Log.i("AgentDbg", "dispatch action=${ev.action} at ${ev.x},${ev.y} handled=$handled")
        return handled
    }

    private fun updateKeyboardSize() {
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        val unset = LayoutParams.UNSET
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }

            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
            }

            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }

            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        broadcaster.clear()
        super.onDetachedFromWindow()
    }
}
