package io.github.tviperf.link

import android.net.TrafficStats
import android.os.Process

/**
 * 测速过程中的实时速率采样。
 *
 * 按**应用自身 UID** 采样：`TrafficStats.getUidRxBytes()/getUidTxBytes()`。
 * iperf3 子进程由本应用 fork，UID 相同，所以它的流量会被算进来。
 * 这条路径无需任何权限、无需 root。
 *
 * 注意不要改用 `/proc/net/dev` —— 那要读 procfs，免 root 拿不到，
 * Android 10 起更是明确禁止。
 */
class LiveRateSampler(
    private val intervalMs: Long = 1000L,
    private val onSample: (rxMbps: Double, txMbps: Double, rxTotal: Long, txTotal: Long) -> Unit
) {

    @Volatile
    private var running = false

    private var worker: Thread? = null

    /** 采样是否可用。部分机型/ROM 上 TrafficStats 对该 UID 返回 UNSUPPORTED。 */
    @Volatile
    var supported: Boolean = true
        private set

    fun start() {
        if (running) return
        running = true

        val uid = Process.myUid()
        var lastRx = TrafficStats.getUidRxBytes(uid)
        var lastTx = TrafficStats.getUidTxBytes(uid)
        var lastAt = System.nanoTime()

        if (lastRx == TrafficStats.UNSUPPORTED.toLong() || lastTx == TrafficStats.UNSUPPORTED.toLong()) {
            supported = false
        }

        worker = Thread {
            while (running) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break

                val nowRx = TrafficStats.getUidRxBytes(uid)
                val nowTx = TrafficStats.getUidTxBytes(uid)
                val nowAt = System.nanoTime()

                if (nowRx == TrafficStats.UNSUPPORTED.toLong() || nowTx == TrafficStats.UNSUPPORTED.toLong()) {
                    supported = false
                    break
                }

                val elapsed = (nowAt - lastAt).coerceAtLeast(1L) / 1_000_000_000.0
                val rxMbps = ((nowRx - lastRx).coerceAtLeast(0L) * 8.0) / elapsed / 1_000_000.0
                val txMbps = ((nowTx - lastTx).coerceAtLeast(0L) * 8.0) / elapsed / 1_000_000.0

                lastRx = nowRx
                lastTx = nowTx
                lastAt = nowAt

                try {
                    onSample(rxMbps, txMbps, nowRx, nowTx)
                } catch (_: Throwable) {
                    // 回调里出错不能打断采样
                }
            }
        }.apply {
            isDaemon = true
            name = "live-rate-sampler"
            start()
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
