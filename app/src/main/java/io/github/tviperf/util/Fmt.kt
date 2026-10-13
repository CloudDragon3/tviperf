package io.github.tviperf.util

import java.util.Locale

/** 统一的数字格式化。全部显式用 Locale.US，避免某些区域设置把小数点渲染成逗号。 */
object Fmt {

    /** 吞吐：主结果用整数 Mbps，小数值保留一位。 */
    fun mbps(v: Double?): String {
        if (v == null) return "----"
        return if (v >= 100.0) {
            String.format(Locale.US, "%.0f Mbps", v)
        } else {
            String.format(Locale.US, "%.1f Mbps", v)
        }
    }

    /** 判定结论里用的短数字 */
    fun mbpsShort(v: Double?): String =
        if (v == null) "----" else String.format(Locale.US, "%.0f", v)

    fun oneDecimal(v: Double?): String =
        if (v == null) "--" else String.format(Locale.US, "%.1f", v)

    fun gbpsOrMbps(v: Double?): String {
        if (v == null) return "----"
        return if (v >= 1000.0) String.format(Locale.US, "%.2f Gbps", v / 1000.0)
        else String.format(Locale.US, "%.0f Mbps", v)
    }
}
