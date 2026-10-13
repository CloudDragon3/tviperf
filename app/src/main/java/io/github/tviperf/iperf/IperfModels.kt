package io.github.tviperf.iperf

/**
 * 四项固定测试。参数顺序即执行顺序（TCP 下行 → TCP 上行 → UDP 下行 → UDP 上行）。
 *
 * @param reverse true 表示加 `-R`，由服务端向 TV 发数据（下行），也就是播放场景真正吃的那条路。
 * @param udp     true 表示 UDP 测试，关注抖动与丢包而不是纯吞吐。
 */
enum class IperfTest(
    val label: String,
    val reverse: Boolean,
    val udp: Boolean
) {
    TCP_DOWN("TCP 下行 (服务端→TV)", true, false),
    TCP_UP("TCP 上行 (TV→服务端)", false, false),
    UDP_DOWN("UDP 下行 (服务端→TV)", true, true),
    UDP_UP("UDP 上行 (TV→服务端)", false, true);

    /** 只有 TCP 才有重传统计 */
    val hasRetransmit: Boolean get() = !udp
}

/**
 * 单项测试的结果。任何字段为 null 都表示"这项没拿到"，UI 上显示占位符而不是 0——
 * 把"没测到"渲染成 0 会直接误导排障。
 */
data class IperfSample(
    val test: IperfTest,
    val ok: Boolean,
    val mbps: Double? = null,
    val retransmits: Long? = null,
    val jitterMs: Double? = null,
    val lostPercent: Double? = null,
    val seconds: Double? = null,
    val note: String? = null,
    val rawJson: String? = null
)
