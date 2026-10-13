package io.github.tviperf.iperf

import org.json.JSONObject

/**
 * 解析 iperf3 `-J` 的 JSON 输出。
 *
 * 字段优先级说明（TCP）：主结论取 `end.sum_received.bits_per_second`，
 * 也就是"接收端真正落地了多少"。用发送端数字会掩盖链路丢包——
 * 发送端觉得自己发出去了 900 Mbps，接收端只收到 420 Mbps，那链路就是 420。
 *
 * UDP 则取 `end.sum.bits_per_second`，并额外读 jitter / lost_percent。
 */
object IperfResultParser {

    fun parse(test: IperfTest, stdout: String): IperfSample {
        val jsonText = extractJson(stdout)
            ?: return IperfSample(
                test = test, ok = false,
                note = if (stdout.isBlank()) "没有任何输出" else "输出里找不到 JSON（前 200 字：" + stdout.take(200) + "）"
            )

        return try {
            parseJson(test, jsonText)
        } catch (t: Throwable) {
            IperfSample(test, false, note = "JSON 解析失败：${t.javaClass.simpleName} ${t.message}", rawJson = jsonText)
        }
    }

    private fun parseJson(test: IperfTest, jsonText: String): IperfSample {
        val root = JSONObject(jsonText)

        // iperf3 3.17+ 在出错时也会吐 JSON：{"error":"..."}
        root.optJSONObject("error")?.let {
            return IperfSample(test, false, note = "iperf3 报错：" + it.optString("error", "未知错误"))
        }

        val end = root.optJSONObject("end")
            ?: return IperfSample(test, false, note = "JSON 缺少 end 节点（服务端可能提前中断）", rawJson = jsonText)

        val sumSent = end.optJSONObject("sum_sent")
        val sumRecv = end.optJSONObject("sum_received")
        val sumAll = end.optJSONObject("sum")

        val mbps = bitsPerSecond(test, sumAll, sumRecv, sumSent)?.let { it / 1_000_000.0 }

        val retrans = optLong(sumSent, "retransmits")
        val jitter = optNum(sumAll, "jitter_ms") ?: optNum(sumRecv, "jitter_ms")
        val lost = optNum(sumAll, "lost_percent") ?: optNum(sumRecv, "lost_percent")
        val seconds = optNum(sumRecv, "seconds") ?: optNum(sumSent, "seconds")

        if (mbps == null) {
            return IperfSample(test, false, note = "JSON 里没有吞吐字段", rawJson = jsonText)
        }

        return IperfSample(
            test = test,
            ok = true,
            mbps = mbps,
            retransmits = retrans,
            jitterMs = jitter,
            lostPercent = lost,
            seconds = seconds,
            rawJson = jsonText
        )
    }

    private fun bitsPerSecond(
        test: IperfTest,
        sumAll: JSONObject?,
        sumRecv: JSONObject?,
        sumSent: JSONObject?
    ): Double? = if (test.udp) {
        optNum(sumAll, "bits_per_second")
            ?: optNum(sumRecv, "bits_per_second")
            ?: optNum(sumSent, "bits_per_second")
    } else {
        optNum(sumRecv, "bits_per_second")
            ?: optNum(sumSent, "bits_per_second")
            ?: optNum(sumAll, "bits_per_second")
    }

    private fun optNum(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key)) return null
        if (o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN() || d.isInfinite()) null else d
    }

    private fun optLong(o: JSONObject?, key: String): Long? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d.toLong()
    }

    /** iperf3 -J 正常时 stdout 就是纯 JSON；但偶尔会混入告警行，用首尾花括号兜底裁剪。 */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
