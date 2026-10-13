package io.github.tviperf.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import io.github.tviperf.R
import io.github.tviperf.databinding.ActivityDiagBinding
import io.github.tviperf.iperf.LastRun
import io.github.tviperf.link.InferredNicProfile
import io.github.tviperf.link.LinkInfoCollector
import io.github.tviperf.link.LinkSnapshot
import io.github.tviperf.link.NetworkLink
import io.github.tviperf.link.UsbNicInfo
import io.github.tviperf.link.UsbNicInspector
import io.github.tviperf.root.EthtoolReader
import io.github.tviperf.root.RootCapability
import io.github.tviperf.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * 设备与链路信息自查面板。
 *
 * 全部字段只有两类：**实测值**（正常显示）和 **推断值**（灰色 + 明确标注）。
 * 绝不能把推断值渲染成和实测值一样的确定语气 —— 那会直接误导排障。
 *
 * 免 root 下读不到的三项（协商速率 / 驱动名称与版本 / USB 链路速率）在这里不出现，
 * 只在 root 增强区里尝试读取。
 */
class DiagPanelActivity : Activity() {

    private lateinit var bind: ActivityDiagBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var snapshot: LinkSnapshot? = null
    private var nic: UsbNicInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityDiagBinding.inflate(layoutInflater)
        setContentView(bind.root)

        bind.btnBack.setOnClickListener { finish() }
        bind.btnRefresh.setOnClickListener { refresh() }
        bind.btnRootProbe.setOnClickListener { probeRoot() }

        bind.btnBack.requestFocus()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        bind.btnRootProbe.isEnabled = false
        bind.dRootResult.text = ""
        bind.dInferTitle.text = "采集中…"
        bind.dInferBody.text = ""

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val s = LinkInfoCollector.collect(applicationContext)
                val u = UsbNicInspector.primaryNic(UsbNicInspector.inspect(applicationContext))
                Pair(s, u)
            }
            snapshot = result.first
            nic = result.second

            renderFacts(result.first, result.second)
            renderInference(result.first, result.second)

            // root 不可用就把按钮置灰 —— 不报错、不崩溃、不卡住 UI
            thread(name = "root-probe") {
                val available = RootCapability.isRootAvailable()
                runOnUiThread {
                    bind.btnRootProbe.isEnabled = available
                    if (!available) {
                        bind.dRootResult.text = "未检测到 root（su 不可用或授权超时）。该项已置灰。"
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------
    // 左栏：能读到的实测事实
    // ------------------------------------------------------------

    private fun renderFacts(s: LinkSnapshot, u: UsbNicInfo?) {
        val carrier = s.carrier
        val ifaceName = s.carrierInterfaceName

        bind.dIface.text = ifaceName ?: "不可用"

        val mac = carrier?.mac
        bind.dMac.text = when {
            mac == null -> "不可用"
            carrier.macRandomized -> "$mac  （已随机化，OUI 反查无效）"
            else -> mac
        }

        val net = s.networks.firstOrNull { it.interfaceName == ifaceName }
        bind.dIp.text = if (net?.ipv4 != null) {
            "${net.ipv4}/${net.prefixLength}"
        } else {
            carrier?.ipv4 ?: "不可用"
        }

        bind.dGw.text = s.carrierGateway ?: "不可用"
        bind.dMtu.text = carrier?.mtu?.toString() ?: "不可用"
        bind.dTransport.text = describeTransport(s, net)

        if (u == null) {
            bind.dUsbVidPid.text = "不可用"
            bind.dUsbClass.text = "不可用"
            bind.dUsbVersion.text = "不可用"
        } else {
            bind.dUsbVidPid.text = "${u.vidPid}  ${u.chipGuess ?: ""}".trim()
            bind.dUsbClass.text = UsbNicInspector.describeClasses(u.interfaceClasses)
            // 这一项极易被误读成"USB 协议版本"，字段名必须写清是"设备/固件版本号"
            bind.dUsbVersion.text = "${u.deviceVersionBcd}（bcdDevice，非 USB 协议版本）"
        }
    }

    private fun describeTransport(s: LinkSnapshot, net: NetworkLink?): String {
        val names = (net?.transports ?: s.carrierTransports)
            .map { LinkInfoCollector.transportName(it) }
        val base = if (names.isEmpty()) "不可用" else names.joinToString(" + ")
        val extra = if (s.wifiConnected && !s.carrierIsWifi) "  （Wi-Fi 也连着，但当前不承载流量）" else ""
        return base + extra
    }

    // ------------------------------------------------------------
    // 右栏：推论（明确标注为推断）
    // ------------------------------------------------------------

    private fun renderInference(s: LinkSnapshot, u: UsbNicInfo?) {
        val inferred = InferredNicProfile.infer(s, u, LastRun.tcpDownMbps)

        bind.dInferTitle.text = inferred.headline
        bind.dInferTitle.setTextColor(
            getColor(
                when {
                    inferred.onWifi -> R.color.warn
                    inferred.cdcSuspicion -> R.color.warn
                    else -> R.color.accent
                }
            )
        )

        val sb = StringBuilder()
        sb.append(inferred.body)

        val down = LastRun.tcpDownMbps
        val up = LastRun.tcpUpMbps
        if (down != null) {
            sb.append("\n\n最近一次实测：TCP 下行 ").append(Fmt.mbpsShort(down)).append(" Mbps")
            up?.let { sb.append("，上行 ").append(Fmt.mbpsShort(it)).append(" Mbps") }
            sb.append("（").append(LastRun.verdict?.title ?: "—").append("）")
        } else {
            sb.append("\n\n还没有测速结果。回主界面跑一次，这里会用实测数字反推链路档位。")
        }

        val estDown = s.carrierLinkDownKbps
        sb.append("\n\n系统估算带宽（仅供参考，非协商速率）：")
        sb.append(if (estDown != null && estDown > 0) "$estDown Kbps" else "不可用（以太网上通常返回 0）")

        sb.append("\n\n以上带「推断」性质的内容均由接口名、VID:PID、接口类与实测吞吐交叉推出，")
        sb.append("不是从硬件读出来的数值。")

        bind.dInferBody.text = sb.toString()
    }

    // ------------------------------------------------------------
    // root 增强
    // ------------------------------------------------------------

    private fun probeRoot() {
        bind.btnRootProbe.isEnabled = false
        bind.dRootResult.text = "读取中…"

        thread(name = "ethool-read") {
            RootCapability.clearCache()
            val info = EthtoolReader.read(snapshot?.carrierInterfaceName)
            val text = EthtoolReader.format(info)
            runOnUiThread {
                bind.dRootResult.text = text
                bind.btnRootProbe.isEnabled = info.rootAvailable
                if (info.hasAny) {
                    bind.btnRootProbe.visibility = View.VISIBLE
                }
            }
        }
    }
}
