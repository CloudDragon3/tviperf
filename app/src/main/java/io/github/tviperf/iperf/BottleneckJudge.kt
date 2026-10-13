package io.github.tviperf.iperf

import io.github.tviperf.link.LinkSnapshot

enum class VerdictLevel { UNKNOWN, GOOD, INFO, WARN, BAD }

data class Verdict(
    val level: VerdictLevel,
    val title: String,
    val detail: String,
    val interfaceLine: String
)

/**
 * 瓶颈判定 —— 本工具相对 iperf3 命令行的核心价值所在。
 * 命令行给数字，这里给结论。
 *
 * 三条必须守住的纪律（来自设计文档 6.4 / 6.5）：
 *
 * 1. **只接受 iperf3 多流结果**。浏览器测速的数字有客户端 JS 引擎天花板，不能喂进来。
 * 2. **接口名必须和吞吐档位放在一起看**。单看一个数字无法区分"主机处理上限"
 *    和"驱动/链路限制"—— 这两种情况的处置方式完全相反。
 * 3. **不承诺做不到的事**。免 root 前提下若瓶颈确实在驱动栈，这个数字改不动，
 *    判定结论要说清楚，而不是给一个乐观的假象。
 *
 * 文案口径（重要）：**Wi-Fi 是合法的链路选择，不是错误**。
 * 用户可能就是要用 Wi-Fi——判定只能说"无线链路的实测能力是 X"，
 * 不能武断地让用户去关 Wi-Fi；只有当用户自己想测有线时才提示切换方法。
 */
object BottleneckJudge {

    fun judge(
        tcpDown: IperfSample?,
        tcpUp: IperfSample?,
        udpDown: IperfSample?,
        udpUp: IperfSample?,
        link: LinkSnapshot?
    ): Verdict {
        val iface = link?.carrierInterfaceName
        val ifaceText = iface ?: "未取到"

        val v = tcpDown?.mbps
        if (tcpDown == null || !tcpDown.ok || v == null) {
            val why = tcpDown?.note
                ?: "TCP 下行没拿到有效数字。先确认服务端 iperf3 在跑、5201 端口可达，然后重测。"
            return Verdict(
                VerdictLevel.UNKNOWN,
                "无法判定",
                why,
                "承载接口：$ifaceText"
            )
        }

        val usbLike = iface != null && (iface.startsWith("usb") || iface.startsWith("wwan"))
        val ethLike = iface != null && iface.startsWith("eth")
        val onWifi = (iface != null && iface.startsWith("wlan")) || link?.carrierIsWifi == true

        val suffix = buildString {
            append("承载接口：").append(ifaceText)
            // Wi-Fi 协商速率是很有用的旁证：实测吞吐明显低于协商速率时，瓶颈多半不在无线本身
            val kbps = link?.carrierLinkDownKbps
            if (onWifi && kbps != null && kbps > 0) {
                append("（Wi-Fi 协商 ").append(io.github.tviperf.util.Fmt.mbpsShort(kbps / 1000.0)).append(" Mbps）")
            }
            if (usbLike) append("（USB 网卡）")
            if (tcpUp?.mbps != null) {
                append("　|　上行 ")
                append(io.github.tviperf.util.Fmt.mbpsShort(tcpUp.mbps)).append(" Mbps")
            }
        }

        val verdict = when {
            onWifi -> wifiVerdict(v, ifaceText, suffix)

            v >= 1500.0 -> Verdict(
                VerdictLevel.GOOD,
                "2.5G 链路打通",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，已超过千兆水位，链路侧没有可疑之处。" +
                    "如果此时仍有播放卡顿，基本可排除「网络带宽不足」，应从播放器缓冲参数、" +
                    "服务端磁盘读取、共享协议、解码能力这些方向找。",
                suffix
            )

            v >= 850.0 -> Verdict(
                VerdictLevel.GOOD,
                "链路健康（千兆档）",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，接近千兆对端的水位。" +
                    "按当前证据，播放卡顿与网络带宽无关。",
                suffix
            )

            v >= 600.0 -> Verdict(
                VerdictLevel.INFO,
                "接近千兆上限",
                buildString {
                    append("多流下行 ").append(io.github.tviperf.util.Fmt.mbpsShort(v)).append(" Mbps。")
                    if (usbLike) {
                        append("USB 网卡走通用驱动时常见这个量级的折损；想跑满 2.5G 通常需要设备端加载原厂驱动（多数设备要 root）。")
                    } else {
                        append("这个区间通常是主机协议栈/CPU 的处理上限，属正常范围，不必折腾。")
                    }
                },
                suffix
            )

            v >= 300.0 -> Verdict(
                VerdictLevel.WARN,
                "千兆链路未跑满",
                buildString {
                    append("多流下行 ").append(io.github.tviperf.util.Fmt.mbpsShort(v)).append(" Mbps，介于百兆与千兆之间。")
                    append("常见原因：网线只有四芯或老化、交换机端口协商异常、网卡驱动上限；")
                    if (usbLike) {
                        append("当前走 USB 网卡，USB 2.0 总线（实测约 280~350 Mbps）也是典型因素，优先换 USB 3.0 口。")
                    } else {
                        append("可换一根确认完好的网线、换交换机端口后再测。")
                    }
                },
                suffix
            )

            v >= 150.0 -> Verdict(
                VerdictLevel.INFO,
                "处于过渡区间",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，位于 150~300 Mbps，" +
                    "没有落在典型档位的中心。建议连续测两三次看是否稳定，并结合设备信息面板的接口名判断。",
                suffix
            )

            else -> Verdict(
                VerdictLevel.BAD,
                "百兆量级链路",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，只有百兆量级。" +
                    "常见原因：电视自带网口是百兆、网线或水晶头老化、交换机端口协商为百兆。" +
                    "在千兆内网环境里这就是当前瓶颈，值得优先解决。",
                suffix
            )
        }

        var result = verdict

        // 重传是高价值补充信号：区分"链路跑不上去"和"链路能跑但沿途在丢"
        val retr = tcpDown.retransmits
        if (retr != null) {
            val extra = when {
                retr == 0L -> "TCP 重传 0 次，链路干净。"
                onWifi -> "TCP 重传 $retr 次；无线环境存在一定重传属常见现象，数值很大时再关注干扰与信号强度。"
                else -> "TCP 重传 $retr 次——沿途有丢包重传，可关注网线质量与交换机端口错误计数。"
            }
            result = result.copy(detail = result.detail + " " + extra)
        }

        // UDP 按 1G 目标速率硬发，丢包率反映的是"容量低于目标速率"，不是故障——必须说清，避免吓到用户
        val udpNote = udpCapacityNote(onWifi, tcpDown, udpDown, udpUp)
        if (udpNote != null) {
            result = result.copy(detail = result.detail + " " + udpNote)
        }

        // 上行显著高于下行：USB 通用驱动最典型的非对称特征（仅限有线 USB 场景提示）
        val up = tcpUp?.mbps
        if (usbLike && up != null && up >= 1.5 * v && up > 300.0) {
            result = result.copy(
                detail = result.detail +
                    " 注意：上行 ${io.github.tviperf.util.Fmt.mbpsShort(up)} Mbps 明显高于下行 " +
                    "${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，这种强非对称在 USB 通用驱动上很典型。"
            )
        }

        return result
    }

    /** Wi-Fi 分支：中立报告，不催用户换有线 */
    private fun wifiVerdict(v: Double, ifaceText: String, suffix: String): Verdict {
        val tier = when {
            v >= 800.0 -> "对无线链路来说已属优秀，接近常见 Wi-Fi 5/6 终端的实际上限"
            v >= 400.0 -> "属无线链路的正常良好水平"
            v >= 150.0 -> "可满足 4K 流媒体播放（蓝光原盘约 40~80 Mbps），但对千兆级文件传输偏慢"
            else -> "偏低：检查电视与路由器的距离、障碍物、频段（2.4G/5G）与同频干扰后复测"
        }
        return Verdict(
            VerdictLevel.INFO,
            "Wi-Fi 链路实测 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps",
            "本次流量经 Wi-Fi（$ifaceText）。这就是当前无线链路的真实吞吐，可直接用来评估" +
                "无线场景下的播放与传输能力——$tier。" +
                "想对比有线链路、或追求更高吞吐时，可改用有线连接再测一轮作对照。",
            suffix
        )
    }

    /**
     * UDP 容量说明：四项里 UDP 是按 1G 目标速率硬发的，链路容量低于 1G 时
     * 高丢包是数学上的必然，必须解释清楚，否则"丢包 73.8%"会把用户吓去查网络故障。
     */
    private fun udpCapacityNote(
        onWifi: Boolean,
        tcpDown: IperfSample?,
        udpDown: IperfSample?,
        udpUp: IperfSample?
    ): String? {
        val sample = udpDown ?: udpUp ?: return null
        if (!sample.ok) return null
        val lost = sample.lostPercent ?: return null
        if (lost < 15.0) return null
        val tcp = tcpDown?.mbps
        return if (tcp != null && tcp >= 900.0) {
            "UDP 下行按 1G 目标速率发送出现 ${"%.1f".format(lost)}% 丢包——链路容量已贴近 1G 目标但略低于它，" +
                "这是接近上限的容量信号，不是网络故障。"
        } else {
            "UDP 测试是按 1G 目标速率硬发的，出现 ${"%.1f".format(lost)}% 丢包只说明链路容量低于 1G" +
                (if (tcp != null) "（与 TCP 实测一致）" else "") +
                "，是预期现象，不代表网络质量有问题。"
        }
    }
}
