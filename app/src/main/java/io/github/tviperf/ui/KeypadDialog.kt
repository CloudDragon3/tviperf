package io.github.tviperf.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.github.tviperf.R

/**
 * 自绘数字键盘。
 *
 * 电视上的软键盘是灾难：遥控器要靠方向键在一个 QWERTY 布局上挪光标。
 * 这里干脆不用系统输入法，只给数字和小数点 —— 输入 IP 和端口足够了。
 */
class KeypadDialog(
    context: Context,
    private val dialogTitle: String,
    initial: String,
    private val isPort: Boolean,
    private val onConfirm: (String) -> Unit
) : Dialog(context) {

    private val buffer = StringBuilder(initial)
    private lateinit var valueView: TextView
    private lateinit var errorView: TextView

    private val maxLen = if (isPort) 5 else 15

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_keypad)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            // 键盘本身不应该弹出系统输入法
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        setCanceledOnTouchOutside(false)

        valueView = findViewById(R.id.tvKeypadValue)
        errorView = findViewById(R.id.tvKeypadError)
        findViewById<TextView>(R.id.tvKeypadTitle).text = dialogTitle

        // 快捷段只在输 IP 时出现
        if (isPort) findViewById<View>(R.id.rowQuick).visibility = View.GONE
        findViewById<Button>(R.id.keyQuick192).setOnClickListener { appendShortcut("192") }
        findViewById<Button>(R.id.keyQuick168).setOnClickListener { appendShortcut("168") }

        bindDigits()
        render()

        findViewById<Button>(R.id.keyDel).setOnClickListener {
            if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
            render()
        }
        findViewById<Button>(R.id.keyClear).setOnClickListener {
            buffer.setLength(0)
            render()
        }
        findViewById<Button>(R.id.keyCancel).setOnClickListener { dismiss() }
        findViewById<Button>(R.id.keyOk).setOnClickListener { confirm() }

        // 遥控器"确定"键直接走确认
        findViewById<Button>(R.id.keyOk).requestFocus()
    }

    private fun bindDigits() {
        val map = mapOf(
            R.id.key0 to "0", R.id.key1 to "1", R.id.key2 to "2", R.id.key3 to "3",
            R.id.key4 to "4", R.id.key5 to "5", R.id.key6 to "6", R.id.key7 to "7",
            R.id.key8 to "8", R.id.key9 to "9", R.id.keyDot to "."
        )
        for ((id, ch) in map) {
            findViewById<Button>(id).setOnClickListener { append(ch) }
        }
    }

    /**
     * 快捷段：把"当前正在输入的那一段"整体替换为 seg（如 192），并自动补上小数点。
     * 这样无论光标位置如何，"192." → "168." → 手输 "123." → "200" 的节奏不会打错：
     * - 当前段为空：直接填入
     * - 当前段已有内容（如 "12"）：替换为快捷段，用户不用先按删除
     * - 已是第四段（3 个点）且为空：不再追加（避免 "x.x.x.x." 非法尾巴）
     */
    private fun appendShortcut(seg: String) {
        val dot = buffer.lastIndexOf('.')
        val cur = if (dot < 0) buffer.toString() else buffer.substring(dot + 1)
        val head = if (dot < 0) "" else buffer.substring(0, dot + 1)
        if (cur.isEmpty() && buffer.count { it == '.' } >= 3) return
        buffer.setLength(0)
        buffer.append(head).append(seg)
        if (buffer.count { it == '.' } < 3) buffer.append('.')
        render()
    }

    private fun append(ch: String) {
        if (buffer.length >= maxLen) return
        if (ch == ".") {
            if (isPort) return                          // 端口不含小数点
            if (buffer.isEmpty()) return                // 不能以 . 开头
            if (buffer.last() == '.') return            // 不允许连续两个 .
            if (buffer.count { it == '.' } >= 3) return // IPv4 共 4 段，最多 3 个 .
        } else {
            // 数字：按 . 分段，每段最多 3 位
            val seg = buffer.substring(buffer.lastIndexOf('.') + 1)
            if (seg.length >= 3) return
        }
        buffer.append(ch)
        render()
    }

    private fun render() {
        valueView.text = if (buffer.isEmpty()) "—" else buffer.toString()
        errorView.text = ""
    }

    private fun confirm() {
        val text = buffer.toString().trim()
        val problem = validate(text)
        if (problem != null) {
            errorView.text = problem
            return
        }
        onConfirm(text)
        dismiss()
    }

    private fun validate(text: String): String? {
        if (text.isEmpty()) return "不能为空"
        if (isPort) {
            val p = text.toIntOrNull() ?: return "端口必须是数字"
            if (p !in 1..65535) return "端口范围 1~65535"
            return null
        }
        val parts = text.split(".")
        if (parts.size != 4) return "IP 需要四段，例如 192.168.1.100"
        for (seg in parts) {
            if (seg.isEmpty() || seg.length > 3) return "每段 0~255"
            val n = seg.toIntOrNull() ?: return "每段必须是数字"
            if (n !in 0..255) return "每段 0~255"
        }
        return null
    }
}
