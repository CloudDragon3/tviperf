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
 * 2. **接口名必须和吞吐档位放在一起看**。单看一个数字无法区分"CPU / 测量工具限制"
 *    和"驱动栈限制"—— 这两种情况的处置方式完全相反。
 * 3. **不承诺做不到的事**。免 root 前提下若瓶颈确实在 cdc_ncm，这个数字改不动，
 *    判定结论要说清楚，而不是给一个乐观的假象。
 */
object BottleneckJudge {

    fun judge(
        tcpDown: IperfSample?,
        tcpUp: IperfSample?,
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
            if (usbLike) append("（USB/CDC 命名空间）")
            else if (ethLike) append("（专用以太网命名空间）")
            if (tcpUp?.mbps != null) {
                append("　|　上行 ")
                append(io.github.tviperf.util.Fmt.mbpsShort(tcpUp.mbps)).append(" Mbps")
            }
        }

        val verdict = when {
            onWifi -> Verdict(
                VerdictLevel.WARN,
                "流量走了 Wi-Fi",
                "接口名是 $ifaceText，说明当前承载流量的是无线，USB 网卡没被选中。" +
                    "这个数字不能代表有线链路的真实能力。关掉电视 Wi-Fi 后重测。",
                suffix
            )

            v >= 1500.0 -> Verdict(
                VerdictLevel.GOOD,
                "2.5G 链路真正打通",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，超过千兆水位，链路侧没有任何可疑之处。" +
                    "如果此时仍有播放卡顿，基本可以排除「网络带宽不足」，应从 Kodi 缓冲参数、" +
                    "服务端磁盘读取、共享协议实现、解码能力这些方向找。",
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
                if (usbLike) "驱动可用但有折损" else "链路正常，瓶颈在主机侧",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，位于 600~850 Mbps 区间。" +
                    if (usbLike)
                        "接口名是 $ifaceText，偏 cdc_ncm 路径；若要把 2.5G 跑满需要 root + 加载原厂 .ko 驱动模块。"
                    else
                        "链路本身正常，这个数字通常是主机 CPU 在处理协议栈时的上限，属于正常范围，不必折腾。",
                suffix
            )

            v in 400.0..600.0 -> Verdict(
                VerdictLevel.WARN,
                if (usbLike) "疑似 cdc_ncm 通用驱动的下行天花板" else "吞吐偏低，需要继续定位",
                buildString {
                    append("多流下行 ").append(io.github.tviperf.util.Fmt.mbpsShort(v)).append(" Mbps。")
                    if (usbLike) {
                        append("接口名是 ").append(ifaceText)
                        append("，且数字正好落在 cdc_ncm 通用驱动的下行典型上限（不足 500 Mbps）区间。")
                        append("这与「换成 iperf3 就能测出更高数字」的预期相反 —— 瓶颈在驱动栈，不在测量工具。")
                        append("免 root 无解；唯一出路是 root 后加载 Realtek 原厂驱动模块（r8156_e0, VID:PID=0bda:8156）。")
                    } else {
                        append("这个档位介于「USB 2.0 总线」和「cdc_ncm 驱动上限」之间，需结合接口名与 USB 口版本判断。")
                    }
                },
                suffix
            )

            v in 300.0..400.0 -> Verdict(
                VerdictLevel.WARN,
                "卡在 300~400 Mbps 档位",
                buildString {
                    append("多流下行 ").append(io.github.tviperf.util.Fmt.mbpsShort(v)).append(" Mbps。")
                    append("两种可能：一是退化到 cdc_ncm 通用驱动，二是网卡仍挂在 USB 2.0 总线上")
                    append("（USB 2.0 理论上限 480 Mbps、实测约 280~350 Mbps）。")
                    if (usbLike) append("当前接口名是 ").append(ifaceText).append("，先确认网卡插在 USB 3.0 那个口。")
                },
                suffix
            )

            v < 150.0 -> Verdict(
                VerdictLevel.BAD,
                "疑似走了百兆链路",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，只有百兆量级。" +
                    "大概率是 USB 网卡根本没被选中（流量落到电视自带的百兆网口），" +
                    "或者网卡协商降级到了 100 Mbps。先看设备信息面板里的接口名，" +
                    "再检查交换机端口协商状态。这种情况对排查极有价值，务必先解决。",
                suffix
            )

            else -> Verdict(
                VerdictLevel.INFO,
                "处于常规区间之间",
                "多流下行 ${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，位于 150~300 Mbps 的过渡档位，" +
                    "没有落在任何一个典型模式的中心。建议连续测三次看是否稳定，再结合接口名判断。",
                suffix
            )
        }

        // 重传是高价值补充信号：区分"链路跑不上去"和"链路能跑但沿途在丢"
        val retr = tcpDown.retransmits
        val withRetr = if (retr != null) {
            val extra = if (retr == 0L) {
                "TCP 重传 0 次，链路干净。"
            } else {
                "TCP 重传 $retr 次 —— 链路能跑但沿途有丢包，可关注网线质量与交换机端口错误计数。"
            }
            verdict.copy(detail = verdict.detail + " " + extra)
        } else {
            verdict
        }

        // 上行显著高于下行：cdc_ncm 最典型的非对称特征，值得单独点出来
        val up = tcpUp?.mbps
        if (up != null && up >= 1.5 * v && up > 300.0) {
            return withRetr.copy(
                detail = withRetr.detail +
                    " 注意：上行 ${io.github.tviperf.util.Fmt.mbpsShort(up)} Mbps 明显高于下行 " +
                    "${io.github.tviperf.util.Fmt.mbpsShort(v)} Mbps，这种强非对称是 cdc_ncm 通用驱动的典型特征" +
                    "（该驱动上行约 750 Mbps / 下行不足 500 Mbps）。"
            )
        }

        return withRetr
    }
}
