package io.github.tviperf.link

/**
 * 免 root 条件下的"网卡档位"推断模型（设计文档 7.4）。
 *
 * 这一层必须足够诚实：输入是**能读到的**事实（接口名 / VID:PID / 接口类 / MTU / 实测吞吐），
 * 输出是**推断结论**，不是硬件读数。UI 上要和实测值做视觉区分。
 */
data class NicInference(
    val headline: String,
    val body: String,
    /** 是否疑似退化到 cdc_ncm 通用驱动 —— 这是本机当前最需要警惕的一种情况 */
    val cdcSuspicion: Boolean,
    /** 当前是否在走 Wi-Fi，把 USB 网卡晾在一边 */
    val onWifi: Boolean
)

object InferredNicProfile {

    fun infer(snapshot: LinkSnapshot?, usb: UsbNicInfo?, tcpDownMbps: Double?): NicInference {
        if (snapshot == null) {
            return NicInference("信息不可用", "采集链路信息时发生异常，测速主流程不受影响。", false, false)
        }

        val iface = snapshot.carrierInterfaceName
        val onWifi = snapshot.carrierIsWifi || (iface?.startsWith("wlan") == true)

        val lines = ArrayList<String>(8)

        if (iface == null) {
            lines += "当前没有取到承载流量的接口名 —— 电视可能没连网，或系统限制了接口枚举。"
        } else {
            lines += "承载接口：$iface（${snapshot.driverHypothesis}）"
        }

        if (usb != null) {
            lines += "外接 USB 设备：${usb.chipGuess ?: "未知型号"}  VID:PID=${usb.vidPid}"
            if (usb.cdcLike) {
                lines += "USB 接口类为 CDC(0x02+0x0A)：设备按标准 CDC 暴露，系统倾向用 cdc_ncm。"
            } else if (usb.vendorSpecific) {
                lines += "USB 接口类为 0xFF 厂商自定义：更可能挂原厂驱动。"
            }
        }

        val usbIface = iface?.startsWith("usb") == true || iface?.startsWith("wwan") == true
        val ethIface = iface?.startsWith("eth") == true

        var cdcSuspicion = false
        var headline: String

        when {
            onWifi -> {
                headline = "当前在走 Wi-Fi"
                lines += "流量由 Wi-Fi 承载——这是完全合法的链路选择，Wi-Fi 实测吞吐可直接用于评估无线场景能力。"
                lines += "若设备上插着有线网卡，它此刻未被选中；想测有线链路时，先关闭电视 Wi-Fi 让流量切过去。"
            }

            usbIface -> {
                headline = "疑似 cdc_ncm 通用驱动"
                cdcSuspicion = true
                lines += "接口名是 $iface，免 root 下这基本等同于走了 cdc_ncm 通用驱动路径。"
                lines += "该驱动是纯软件逐包处理（拆 NTB、解析 NDP），下行吞吐容易封顶在 500 Mbps 附近。"
                if (tcpDownMbps != null && tcpDownMbps in 350.0..600.0) {
                    lines += "实测下行 ${"%.0f".format(tcpDownMbps)} Mbps，正好落在该驱动的典型上限区间 —— 数字与驱动特征吻合。"
                }
            }

            ethIface -> {
                headline = "走的是专用以太网驱动"
                lines += "接口名是 $iface，说明厂商/专用驱动挂载正常，链路没有被降级成通用 CDC 路径。"
            }

            else -> {
                headline = "接口类型无法判断"
                lines += "接口名 $iface 不在常见的 eth/usb/wlan 命名里，只能靠实测吞吐反推档位。"
            }
        }

        if (tcpDownMbps != null) {
            lines += "实测 TCP 下行 ${"%.0f".format(tcpDownMbps)} Mbps → 参考档位：" +
               tierDescription(tcpDownMbps, iface, usbIface, ethIface)
        }

        val mt = snapshot.carrier?.mtu
        if (mt != null && mt != 1500) {
            lines += "MTU=$mt（不是 1500），链路可能套了隧道或做了特殊封装。"
        }

        return NicInference(headline, lines.joinToString("\n"), cdcSuspicion, onWifi)
    }

    private fun tierDescription(v: Double, iface: String?, usbIface: Boolean, ethIface: Boolean): String = when {
        v >= 1500.0 -> "1500+ Mbps，2.5G 链路真正打通"
        v >= 850.0 -> "850~1000 Mbps，千兆对端下的正常水平"
        v in 600.0..850.0 ->
            if (usbIface) "600~850 Mbps，USB 通用驱动常见折损区间（接口 $iface）"
            else "600~850 Mbps，属正常范围，瓶颈在主机 CPU"
        v in 400.0..550.0 ->
            if (usbIface) "400~550 Mbps，典型的 USB 通用驱动下行天花板"
            else "400~550 Mbps，需要结合接口名继续判断"
        v in 300.0..400.0 ->
            if (usbIface) "300~400 Mbps，可能是 USB 通用驱动或仍挂在 USB 2.0 总线上"
            else "300~400 Mbps，检查网线与交换机端口，或网卡是否挂在 USB 2.0 总线"
        v < 150.0 -> "不足 150 Mbps，疑似走了电视自带百兆网口，或链路协商降级到百兆"
        else -> "位于常规区间之间的过渡档位"
    }
}
