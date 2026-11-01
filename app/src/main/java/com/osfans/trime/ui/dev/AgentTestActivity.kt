/*
 * Debug-only harness used to exercise the Agent panel against a plain host
 * editor without depending on third-party apps.
 */
package com.osfans.trime.ui.dev

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class AgentTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            TextView(this).apply {
                text = "Agent IME 测试输入框"
                textSize = 16f
            },
        )
        val edit = EditText(this).apply {
            hint = "在这里输入…"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 2
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        root.addView(edit)

        // 放在顶部，避免被 Agent 面板遮住：聚焦时全选旧文本，用来验证「改写」。
        root.addView(
            TextView(this).apply {
                text = "改写测试（聚焦即全选旧文本）"
                textSize = 14f
                setPadding(0, pad, 0, 0)
            },
        )
        val rewriteTarget = EditText(this).apply {
            setText("旧的文本")
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 1
        }
        rewriteTarget.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) (v as EditText).setSelection(0, v.text.length)
        }
        root.addView(rewriteTarget)

        root.addView(
            TextView(this).apply {
                text = "普通域（带 NO_SUGGESTIONS，验证不被误判敏感）"
                textSize = 14f
                setPadding(0, pad, 0, 0)
            },
        )
        val noSuggest = EditText(this).apply {
            hint = "NO_SUGGESTIONS 普通输入…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 1
        }
        root.addView(noSuggest)

        root.addView(
            TextView(this).apply {
                text = "敏感域（密码）"
                textSize = 14f
                setPadding(0, pad, 0, 0)
            },
        )
        val password = EditText(this).apply {
            hint = "密码框"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 1
        }
        root.addView(password)

        setContentView(root)
        edit.requestFocus()
    }
}
