package io.github.tviperf.iperf

/**
 * 最近一次测速的进程内快照，供设备信息面板复用（避免面板上再跑一遍测速）。
 * 只在内存里，不落盘、不上报。
 */
object LastRun {

    @Volatile
    var samples: Map<IperfTest, IperfSample> = emptyMap()

    @Volatile
    var verdict: Verdict? = null

    @Volatile
    var finishedAtMillis: Long = 0L

    val tcpDownMbps: Double? get() = samples[IperfTest.TCP_DOWN]?.takeIf { it.ok }?.mbps
    val tcpUpMbps: Double? get() = samples[IperfTest.TCP_UP]?.takeIf { it.ok }?.mbps

    val hasResult: Boolean get() = finishedAtMillis > 0L && samples.isNotEmpty()

    fun update(samples: Map<IperfTest, IperfSample>, verdict: Verdict?) {
        this.samples = LinkedHashMap(samples)
        this.verdict = verdict
        this.finishedAtMillis = System.currentTimeMillis()
    }

    fun clear() {
        samples = emptyMap()
        verdict = null
        finishedAtMillis = 0L
    }
}
