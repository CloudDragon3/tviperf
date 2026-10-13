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
 * 三个必须守住的地方：
 * 1. **stdout / stderr 必须各起一个线程异步消费**。子进程输出量很大，
 *    管道缓冲区（通常 64KB）写满之后子进程会永久阻塞，表现为"测速卡死不动"。
 * 2. **路径必须从 nativeLibraryDir 取**，这个路径每次安装都会变，不能硬编码，
 *    也不能换成 filesDir —— Android 10+ 的 W^X 策略会拒绝 execve。
 * 3. **必须给子进程注入 TMPDIR**，见 [tempDirForIperf] 的注释。这是 Android
 *    上最容易踩、且报错信息完全误导人的一个坑。
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
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"
        return when {
            !f.exists() -> "找不到 $BIN_NAME（nativeLibraryDir=${f.parent}）"
            !f.canRead() -> "$BIN_NAME 不可读：${f.absolutePath}"
            !f.canExecute() -> "$BIN_NAME 存在但不可执行：${f.absolutePath}"
            // 正常时只报 ABI 与库版本，不刷整条安装路径（太长，会把配置行撑到换行）
            else -> "就绪 ｜ ABI: $abi ｜ iperf3 ${IPERF_VERSION_LABEL}"
        }
    }

    fun reset() {
        cancelled.set(false)
    }

    /**
     * iperf3 的**每一条流**都要在临时目录里 `mkstemp()` 建一个缓冲区文件（默认 128KB），
     * 见 `iperf_new_stream()`：
     *
     * ```c
     * tempdir = getenv("TMPDIR") ?: getenv("TEMP") ?: getenv("TMP");
     * if (!tempdir) {
     * #if defined(__ANDROID__)
     *     tempdir = "/data/local/tmp";   // iperf3 自带的 Android 兜底
     * #else
     *     tempdir = "/tmp";
     * #endif
     * }
     * sp->buffer_fd = mkstemp(template);
     * if (sp->buffer_fd == -1) { i_errno = IECREATESTREAM; return NULL; }
     * ```
     *
     * 而 `IECREATESTREAM` 在 `iperf_error.c` 里同时置了 `perr = 1`（会拼上 `strerror(errno)`），
     * 所以真实报错长这样：
     *
     * > iperf3: error - unable to create a new stream: Permission denied
     *
     * **Android 上两条兜底路径应用都写不进去**：`/data/local/tmp` 属于 shell 用户
     * （权限 0771，普通 app uid 无写权限），`/tmp` 在多数 Android 上根本不存在。
     * 于是 `mkstemp` 必然 EACCES —— 报错文案指向"socket/权限"，实际和网络毫无关系，
     * 极容易误判成服务端权限问题。
     *
     * 另一个容易误导的点：iperf3 是**先 connect 拿 socket、再建缓冲区**，
     * 所以服务端日志里能看到"连接被接受"，随后客户端立刻退出。
     *
     * 因此必须显式把 TMPDIR 指到应用有写权限的目录。用 cacheDir 而不用 filesDir：
     * 系统在存储紧张时可以自动清理 cache，这些缓冲区文件用完就 unlink，不需要长期保留。
     */
    private fun tempDirForIperf(): File {
        val candidates = listOf(
            File(appContext.cacheDir, "iperf3tmp"),
            appContext.cacheDir,
            appContext.noBackupFilesDir
        )
        for (d in candidates) {
            if (d == null) continue
            if (!d.exists() && !d.mkdirs()) continue
            if (d.isDirectory && d.canWrite()) return d
        }
        // 理论上到不了这里；真到了就让 iperf3 用它的默认路径，报错也会带上原始原因
        return appContext.cacheDir
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

        val tmp = tempDirForIperf()

        val process = try {
            ProcessBuilder(listOf(bin.absolutePath) + args)
                .apply {
                    val env = environment()
                    // 见 tempDirForIperf()：不注入 TMPDIR，iperf3 会在 Android 上
                    // 因 mkstemp 拿不到写权限而死于 "unable to create a new stream: Permission denied"
                    env["TMPDIR"] = tmp.absolutePath
                    env["TMP"] = tmp.absolutePath
                    env["TEMP"] = tmp.absolutePath
                    // 二进制是 NDK 静态编译的单文件（无 OpenSSL、无 libiperf.so 依赖），
                    // 不需要 LD_LIBRARY_PATH；nativeLibraryDir 仅供 execve（W^X 策略）。
                }
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

        /** 与 jniLibs 里打包的二进制保持一致；升级二进制时同步改这里 */
        const val IPERF_VERSION_LABEL = "3.22（自编译）"
    }
}
