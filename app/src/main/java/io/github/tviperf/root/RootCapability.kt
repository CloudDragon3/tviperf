package io.github.tviperf.root

import java.util.concurrent.TimeUnit

/**
 * root 能力探测。**默认关闭**，只在用户主动点按钮时才跑。
 *
 * 纪律：所有 su 调用必须包在超时与异常兜底里。su 在没有 root 的设备上
 * 可能既不返回也不报错（弹窗等待授权），如果不加超时会直接把 UI 卡死。
 */
object RootCapability {

    private const val TIMEOUT_MS = 1000L

    @Volatile
    private var cached: Boolean? = null

    fun isRootAvailable(): Boolean {
        cached?.let { return it }
        val result = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val done = p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!done) {
                try {
                    p.destroyForcibly()
                } catch (_: Throwable) {
                }
                false
            } else {
                p.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
        }
        cached = result
        return result
    }

    /**
     * 用 su 执行一条命令并取回输出。
     * @return 输出内容；未 root / 超时 / 失败时返回 null
     */
    fun runAsRoot(command: String, timeoutMs: Long = 2500L): String? {
        if (!isRootAvailable()) return null
        return try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                try {
                    p.destroyForcibly()
                } catch (_: Throwable) {
                }
                return null
            }
            val text = p.inputStream.bufferedReader().use { it.readText() }
            if (p.exitValue() == 0) text.trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCache() {
        cached = null
    }
}
