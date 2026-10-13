package io.github.tviperf.iperf

/**
 * iperf3 参数组装。
 *
 * 四项测试全部使用同一组参数，保证结果可重复、可横向比较，也和设计文档 5.3 的命令行完全一致：
 *
 *   TCP 下行:  -c <服务端> -p 5201 -R -t 10 -P 8 -J
 *   TCP 上行:  -c <服务端> -p 5201    -t 10 -P 8 -J
 *   UDP 下行:  -c <服务端> -p 5201 -R -u -b 1G -t 10 -J
 *   UDP 上行:  -c <服务端> -p 5201    -u -b 1G -t 10 -J
 */
object IperfArgs {

    const val DEFAULT_PORT = 5201
    const val DURATION_SEC = 10

    /**
     * TCP 必须是多流。电视 SoC 单线程 TCP 通常只有 300~900 Mbps，
     * 单流测出来的数字反映的是"单核能力"而不是"链路能力"，会严重低估。
     * 8 条流同时也能让 iperf3 把每条流分配到不同 CPU 核心（3.16+ 的行为）。
     */
    const val TCP_STREAMS = 8

    /** UDP 目标带宽。取 1G 是为了探出丢包/抖动拐点；本机跑不到 1G 也不会报错。 */
    const val UDP_TARGET_BW = "1G"

    fun build(
        test: IperfTest,
        host: String,
        port: Int = DEFAULT_PORT,
        duration: Int = DURATION_SEC,
        streams: Int = TCP_STREAMS
    ): List<String> {
        val a = ArrayList<String>(16)
        a += "-c"; a += host
        a += "-p"; a += port.toString()
        a += "-t"; a += duration.toString()
        if (test.reverse) a += "-R"
        if (test.udp) {
            a += "-u"
            a += "-b"; a += UDP_TARGET_BW
        } else {
            a += "-P"; a += streams.toString()
        }
        // -J 的作用不止"方便解析"：结果不受系统语言/区域设置影响，
        // 而且字段是稳定的，比正则扒人类可读文本可靠得多。
        a += "-J"
        return a
    }

    /** 给 UI 显示用的一行等价命令 */
    fun preview(test: IperfTest, host: String, port: Int): String =
        "iperf3 " + build(test, host, port).joinToString(" ")
}
