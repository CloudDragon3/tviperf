package io.github.tviperf.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import io.github.tviperf.R
import io.github.tviperf.databinding.ActivityMainBinding
import io.github.tviperf.iperf.BottleneckJudge
import io.github.tviperf.iperf.IperfArgs
import io.github.tviperf.iperf.IperfResultParser
import io.github.tviperf.iperf.IperfRunner
import io.github.tviperf.iperf.IperfSample
import io.github.tviperf.iperf.IperfTest
import io.github.tviperf.iperf.LastRun
import io.github.tviperf.iperf.VerdictLevel
import io.github.tviperf.link.InferredNicProfile
import io.github.tviperf.link.LinkInfoCollector
import io.github.tviperf.link.LinkSnapshot
import io.github.tviperf.link.LiveRateSampler
import io.github.tviperf.link.UsbNicInspector
import io.github.tviperf.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var bind: ActivityMainBinding
    private lateinit var runner: IperfRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var sampler: LiveRateSampler? = null

    private val samples = LinkedHashMap<IperfTest, IperfSample>()
    private var linkSnapshot: LinkSnapshot? = null

    private var host: String = ""
    private var port: Int = IperfArgs.DEFAULT_PORT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)

        runner = IperfRunner(applicationContext)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        host = prefs.getString(KEY_HOST, "") ?: ""
        port = prefs.getInt(KEY_PORT, IperfArgs.DEFAULT_PORT)

        bind.btnHost.text = hostDisplay(host)
        bind.btnPort.text = port.toString()

        bind.btnHost.setOnClickListener {
            if (running.get()) return@setOnClickListener
            KeypadDialog(this, getString(R.string.keypad_title), host, isPort = false) { value ->
                host = value
                bind.btnHost.text = hostDisplay(value)
                prefs.edit().putString(KEY_HOST, value).apply()
            }.show()
        }

        bind.btnPort.setOnClickListener {
            if (running.get()) return@setOnClickListener
            KeypadDialog(this, getString(R.string.keypad_title_port), port.toString(), isPort = true) { value ->
                value.toIntOrNull()?.let {
                    port = it
                    bind.btnPort.text = it.toString()
                    prefs.edit().putInt(KEY_PORT, it).apply()
                }
            }.show()
        }

        bind.btnStart.setOnClickListener { startRun() }
        bind.btnCancel.setOnClickListener { cancelRun() }
        bind.btnDiag.setOnClickListener {
            startActivity(Intent(this, DiagPanelActivity::class.java))
        }

        bind.tvCfgHint.text = "产物自检：" + runner.binaryStatus()
        bind.btnStart.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // 测速期间屏幕别黑掉
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (!running.get()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        cancelRun()
        scope.cancel()
        super.onDestroy()
    }

    // ============================================================
    // 主流程
    // ============================================================

    private fun hostDisplay(h: String): String =
        h.ifEmpty { getString(io.github.tviperf.R.string.host_unset) }

    private fun startRun() {
        if (host.isBlank()) {
            // 没填服务器地址时，引导用户先输入
            setStatus(getString(io.github.tviperf.R.string.host_unset))
            bind.btnHost.requestFocus()
            bind.btnHost.performClick()
            return
        }
        if (!running.compareAndSet(false, true)) return
        runner.reset()
        samples.clear()
        renderSamples()
        renderVerdict(null)
        bind.btnCancel.visibility = android.view.View.VISIBLE
        bind.pb.visibility = android.view.View.VISIBLE
        setBusyUi(true)

        job = scope.launch {
            try {
                setStatus("正在采集设备与链路信息…")
                val snapshot = withContext(Dispatchers.IO) { LinkInfoCollector.collect(applicationContext) }
                linkSnapshot = snapshot

                val iface = snapshot.carrierInterfaceName ?: "未取到"
                if (snapshot.carrierIsWifi || iface.startsWith("wlan")) {
                    // R17：本机习惯性关 Wi-Fi，但仍然做成显式提醒，避免测了 Wi-Fi 却当成有线结果
                    setStatus("⚠ 当前承载接口是 $iface（Wi-Fi），测出的是无线链路数字")
                } else {
                    setStatus("承载接口 $iface")
                }

                startSampler()

                val sequence = IperfTest.entries.toList()
                for ((index, test) in sequence.withIndex()) {
                    if (!running.get()) break
                    setStatus("(${index + 1}/${sequence.size}) ${test.label} 进行中…")
                    val args = IperfArgs.build(test, host, port)
                    val outcome = runner.run(args, timeoutMs = (IperfArgs.DURATION_SEC + 20) * 1000L)

                    val sample = when {
                        outcome.startFailed != null ->
                            IperfSample(test, false, note = outcome.startFailed)

                        outcome.timedOut ->
                            IperfSample(test, false, note = "超时未返回（进程已强制结束）")

                        else -> {
                            val parsed = IperfResultParser.parse(test, outcome.stdout)
                            if (!parsed.ok) {
                                var note = parsed.note ?: ""
                                // 诊断：失败时把 stdout 尾巴（debug 状态机/线程生命周期）带到界面
                                if (outcome.stdout.isNotBlank()) {
                                    val tail = outcome.stdout.trim()
                                        .replace(Regex("\\s+"), " ")
                                        .takeLast(360)
                                    note += " ｜ 调试:…" + tail
                                }
                                if (outcome.stderr.isNotBlank()) {
                                    note += " ｜ stderr: " + outcome.stderr.trim().take(200)
                                }
                                parsed.copy(note = note)
                            } else {
                                parsed
                            }
                        }
                    }

                    samples[test] = sample
                    renderSamples()
                    if (!running.get()) break
                }

                if (running.get()) {
                    setStatus("完成")
                    val verdict = BottleneckJudge.judge(
                        samples[IperfTest.TCP_DOWN],
                        samples[IperfTest.TCP_UP],
                        samples[IperfTest.UDP_DOWN],
                        samples[IperfTest.UDP_UP],
                        linkSnapshot
                    )
                    renderVerdict(verdict)
                    // 供设备信息面板复用，避免在面板上再跑一遍测速
                    LastRun.update(samples, verdict)
                } else {
                    setStatus("已中止")
                }
            } catch (t: Throwable) {
                setStatus("出错：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                stopSampler()
                running.set(false)
                setBusyUi(false)
                bind.pb.visibility = android.view.View.INVISIBLE
                bind.btnCancel.visibility = android.view.View.GONE
                bind.btnStart.requestFocus()
            }
        }
    }

    private fun cancelRun() {
        running.set(false)
        sampler?.stop()
        runner.cancel()
        job?.cancel()
        job = null
        bind.pb.visibility = android.view.View.INVISIBLE
        bind.btnCancel.visibility = android.view.View.GONE
        setBusyUi(false)
        setStatus("已中止")
    }

    private fun setBusyUi(busy: Boolean) {
        bind.btnStart.isEnabled = !busy
        bind.btnHost.isEnabled = !busy
        bind.btnPort.isEnabled = !busy
    }

    private fun startSampler() {
        sampler?.stop()
        val s = LiveRateSampler { rx, tx, _, _ ->
            runOnUiThread {
                bind.tvLive.text = "实时 ↓${Fmt.mbpsShort(rx)} ↑${Fmt.mbpsShort(tx)} Mbps"
            }
        }
        sampler = s
        s.start()
    }

    private fun stopSampler() {
        sampler?.stop()
        sampler = null
        bind.tvLive.text = getString(R.string.live_idle)
    }

    // ============================================================
    // 渲染
    // ============================================================

    private fun setStatus(text: String) {
        bind.tvStatus.text = text
    }

    private fun renderSamples() {
        bind.tvTcpDownValue.text = Fmt.mbps(samples[IperfTest.TCP_DOWN]?.mbps)
        bind.tvTcpUpValue.text = Fmt.mbps(samples[IperfTest.TCP_UP]?.mbps)
        bind.tvUdpDownValue.text = Fmt.mbps(samples[IperfTest.UDP_DOWN]?.mbps)
        bind.tvUdpUpValue.text = Fmt.mbps(samples[IperfTest.UDP_UP]?.mbps)

        bind.tvTcpDownSub.text = tcpSub(samples[IperfTest.TCP_DOWN])
        bind.tvTcpUpSub.text = tcpSub(samples[IperfTest.TCP_UP])
        bind.tvUdpDownSub.text = udpSub(samples[IperfTest.UDP_DOWN])
        bind.tvUdpUpSub.text = udpSub(samples[IperfTest.UDP_UP])

        val failed = samples.values.filter { !it.ok }
        bind.tvResultFooter.text = when {
            samples.isEmpty() -> ""
            failed.isEmpty() -> "四项全部完成。TCP 默认 -P ${IperfArgs.TCP_STREAMS} 多流；UDP 目标带宽 ${IperfArgs.UDP_TARGET_BW}。"
            else -> "未完成：" + failed.joinToString("、") { it.test.label } +
                "。首条原因：" + (failed.first().note ?: "未知")
        }
    }

    private fun tcpSub(s: IperfSample?): String {
        if (s == null) return ""
        if (!s.ok) return "未完成"
        return s.retransmits?.let { "重传 $it" } ?: ""
    }

    private fun udpSub(s: IperfSample?): String {
        if (s == null) return ""
        if (!s.ok) return "未完成"
        val j = s.jitterMs?.let { "抖动 ${Fmt.oneDecimal(it)}ms" } ?: ""
        val l = s.lostPercent?.let { "丢包 ${Fmt.oneDecimal(it)}%" } ?: ""
        return listOf(j, l).filter { it.isNotEmpty() }.joinToString(" / ")
    }

    private fun renderVerdict(verdict: io.github.tviperf.iperf.Verdict?) {
        if (verdict == null) {
            bind.tvVerdictLevel.text = "—"
            bind.tvVerdictLevel.setTextColor(getColor(R.color.text_secondary))
            bind.tvVerdictDetail.text = "四项跑完自动给出结论。判定只接受 iperf3 多流结果 —— " +
                "浏览器测速有客户端 CPU 天花板，不能用来判断链路。"
            bind.tvIfaceLine.text = ""
            return
        }

        val color = when (verdict.level) {
            VerdictLevel.GOOD -> R.color.ok
            VerdictLevel.INFO -> R.color.accent
            VerdictLevel.WARN -> R.color.warn
            VerdictLevel.BAD -> R.color.bad
            VerdictLevel.UNKNOWN -> R.color.text_secondary
        }
        bind.tvVerdictLevel.text = verdict.title
        bind.tvVerdictLevel.setTextColor(getColor(color))
        bind.tvVerdictDetail.text = verdict.detail
        bind.tvIfaceLine.text = verdict.interfaceLine
    }

    companion object {
        private const val PREFS = "tviperf"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
    }
}
