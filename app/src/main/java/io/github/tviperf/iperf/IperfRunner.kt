package io.github.tviperf.iperf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拉起打包在 jniLibs 里的 iperf3 可执行文件。
 *
 * 两个必须守住的地方：
 * 1. **stdout / stderr 必须各起一个线程异步消费**。子进程输出量很大，
 *    管道缓冲区（通常 64KB）写满之后子进程会永久阻塞，表现为"测速卡死不动"。
 * 2. **路径必须从 nativeLibraryDir 取**，这个路径每次安装都会变，不能硬编码，
 *    也不能换成 filesDir —— Android 10+ 的 W^X 策略会拒绝 execve。
 */
class IperfRunner(private val appContext: Context) {

    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val startFailed: String? = null
    ) {
        val ok: Boolean get() = startFailed == null && !timedOut && exitCode == 0
    }

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var live: Process? = null

    fun binaryFile(): File = File(appContext.applicationInfo.nativeLibraryDir, BIN_NAME)

    /** 自检信息，直接显示在界面上，避免"起不来但不知道为什么" */
    fun binaryStatus(): String {
        val f = binaryFile()
        return when {
            !f.exists() -> "找不到 $BIN_NAME（nativeLibraryDir=${f.parent}）"
            !f.canRead() -> "$BIN_NAME 不可读：${f.absolutePath}"
            !f.canExecute() -> "$BIN_NAME 存在但不可执行：${f.absolutePath}"
            else -> "就绪：${f.absolutePath}"
        }
    }

    fun reset() {
        cancelled.set(false)
    }

    /** 用户点"中止"或 Activity 退出时调用，保证子进程不残留 */
    fun cancel() {
        cancelled.set(true)
        val p = live
        try {
            p?.destroy()
        } catch (_: Throwable) {
        }
    }

    suspend fun run(
        args: List<String>,
        timeoutMs: Long,
        onStderrLine: ((String) -> Unit)? = null
    ): Outcome = withContext(Dispatchers.IO) {
        if (cancelled.get()) return@withContext Outcome(-1, "", "", true)

        val bin = binaryFile()
        if (!bin.exists()) {
            return@withContext Outcome(
                -1, "", "", false,
                "找不到可执行文件：${bin.absolutePath}（检查 jniLibs 与 useLegacyPackaging）"
            )
        }

        val process = try {
            ProcessBuilder(listOf(bin.absolutePath) + args)
                .redirectErrorStream(false)
                .start()
        } catch (t: Throwable) {
            return@withContext Outcome(-1, "", "", false, "启动子进程失败：${t.message}")
        }

        live = process
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()

        val tOut = Thread { drain(process.inputStream.bufferedReader(), outBuf, null) }
            .apply { isDaemon = true; start() }
        val tErr = Thread { drain(process.errorStream.bufferedReader(), errBuf, onStderrLine) }
            .apply { isDaemon = true; start() }

        var timedOut = false
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                timedOut = true
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
                process.waitFor(2, TimeUnit.SECONDS)
                try {
                    process.destroyForcibly()
                } catch (_: Throwable) {
                }
            }
        } catch (_: InterruptedException) {
            timedOut = true
        }

        // 等输出线程把剩余缓冲读完，但别无限等
        tOut.join(2000)
        tErr.join(2000)
        live = null

        val code = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }

        Outcome(code, outBuf.toString(), errBuf.toString(), timedOut)
    }

    private fun drain(reader: BufferedReader, sink: StringBuilder, onLine: ((String) -> Unit)?) {
        try {
            reader.use { r ->
                r.forEachLine { line ->
                    sink.append(line).append('\n')
                    try {
                        onLine?.invoke(line)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
            // 子进程被杀时读端会抛异常，属正常路径，静默处理
        }
    }

    companion object {
        /**
         * 文件名必须叫 lib*.so。Android 10 起 SELinux 的 W^X 策略禁止执行应用私有目录里的文件，
         * 只有系统在安装时解压到 nativeLibraryDir 的"原生库"目录例外。
         * 所以这里放的是"名字像库、内容是可执行程序"的文件。
         */
        const val BIN_NAME = "libiperf3.so"
    }
}
