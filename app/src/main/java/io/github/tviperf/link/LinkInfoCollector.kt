package io.github.tviperf.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** 单个网络接口的可读事实 */
data class IfaceInfo(
    val name: String,
    val mac: String?,
    val macRandomized: Boolean,
    val mtu: Int?,
    val ipv4: String?,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean
)

/** 一个 Network 的链路属性（来自 ConnectivityManager） */
data class NetworkLink(
    val interfaceName: String?,
    val transports: Set<Int>,
    val isActive: Boolean,
    val linkDownKbps: Int,
    val linkUpKbps: Int,
    val gateway: String?,
    val dnsServers: List<String>,
    val ipv4: String?,
    val prefixLength: Int
)

/**
 * 一次采集的完整快照。所有字段都可能为 null —— 采集失败必须静默降级，
 * 绝不能让"读不到某项信息"影响测速主流程。
 */
data class LinkSnapshot(
    val interfaces: List<IfaceInfo>,
    val networks: List<NetworkLink>,
    val carrierInterfaceName: String?,
    val carrierTransports: Set<Int>,
    val carrierLinkDownKbps: Int?,
    val carrierGateway: String?,
    val carrierDns: List<String>,
    val wifiConnected: Boolean
) {
    val carrier: IfaceInfo? get() = interfaces.firstOrNull { it.name == carrierInterfaceName }

    /** 承载流量的接口是不是有线以太网 */
    val carrierIsEthernet: Boolean
        get() = carrierTransports.contains(NetworkCapabilities.TRANSPORT_ETHERNET)

    val carrierIsWifi: Boolean
        get() = carrierTransports.contains(NetworkCapabilities.TRANSPORT_WIFI)

    /** 免 root 下区分"原厂驱动"还是"cdc_ncm 通用驱动"最关键的线索 */
    val driverHypothesis: String
        get() {
            val n = carrierInterfaceName ?: return "未知（没取到接口名）"
            return when {
                n.startsWith("eth") -> "厂商/专用以太网驱动（如 r8152 / r8156_e0），链路挂载正常"
                n.startsWith("usb") || n.startsWith("wwan") ->
                    "大概率退化到 cdc_ncm 通用驱动，下行吞吐容易封顶在 500 Mbps 附近"
                n.startsWith("wlan") -> "当前在走 Wi-Fi，USB 网卡没被选中"
                else -> "接口名 $n 无法直接对应驱动类型"
            }
        }
}

/**
 * 只使用公开 API 采集链路事实：NetworkInterface + ConnectivityManager。
 *
 * 明确读不到的东西（见设计文档 7.3）：协商速率、驱动名称/版本、USB 实际链路速率。
 * 它们都在 sysfs 里，而 /sys/class/net 下的 SELinux 类型是 sysfs_net，
 * untrusted_app 域没有读权限。不要尝试，失败是必然的。
 */
object LinkInfoCollector {

    fun collect(ctx: Context): LinkSnapshot {
        val interfaces = readInterfaces()
        val networks = readNetworks(ctx)

        val active = networks.firstOrNull { it.isActive }

        // 选"承载流量"的接口：优先有 IPv4 的有线，其次 Wi-Fi，最后活动网络。
        // 电视上同时插了 USB 网卡又连了 Wi-Fi 时，这个判断直接决定结论对不对。
        val carrierNet = networks.firstOrNull {
            it.transports.contains(NetworkCapabilities.TRANSPORT_ETHERNET) && it.ipv4 != null
        } ?: networks.firstOrNull {
            it.transports.contains(NetworkCapabilities.TRANSPORT_USB) && it.ipv4 != null
        } ?: active ?: networks.firstOrNull { it.ipv4 != null }

        val wifiConnected = networks.any {
            it.transports.contains(NetworkCapabilities.TRANSPORT_WIFI) && it.ipv4 != null
        }

        return LinkSnapshot(
            interfaces = interfaces,
            networks = networks,
            carrierInterfaceName = carrierNet?.interfaceName,
            carrierTransports = carrierNet?.transports ?: emptySet(),
            carrierLinkDownKbps = carrierNet?.linkDownKbps,
            carrierGateway = carrierNet?.gateway,
            carrierDns = carrierNet?.dnsServers ?: emptyList(),
            wifiConnected = wifiConnected
        )
    }

    private fun readInterfaces(): List<IfaceInfo> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !it.isLoopback }
            .map { nif ->
                val ipv4 = try {
                    nif.interfaceAddresses
                        .mapNotNull { it.address as? Inet4Address }
                        .firstOrNull { !it.isLoopbackAddress }
                        ?.hostAddress
                } catch (_: Throwable) {
                    null
                }

                val macBytes = try {
                    nif.hardwareAddress
                } catch (_: Throwable) {
                    null
                }
                val mac = macBytes?.joinToString(":") { "%02X".format(it) }

                IfaceInfo(
                    name = nif.name ?: "?",
                    mac = mac,
                    // Android 10+ 对做过 MAC 随机化的接口返回全零或 02:00:00:00:00:00
                    macRandomized = mac == null ||
                        mac == "02:00:00:00:00:00" ||
                        mac == "00:00:00:00:00:00",
                    mtu = try {
                        nif.mtu
                    } catch (_: Throwable) {
                        null
                    },
                    ipv4 = ipv4,
                    isUp = try {
                        nif.isUp
                    } catch (_: Throwable) {
                        false
                    },
                    isLoopback = false,
                    isVirtual = try {
                        nif.isVirtual
                    } catch (_: Throwable) {
                        false
                    }
                )
            }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun readNetworks(ctx: Context): List<NetworkLink> = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val activeId = cm.activeNetwork

        val result = ArrayList<NetworkLink>()
        @Suppress("DEPRECATION")
        val all: Array<Network> = cm.allNetworks ?: emptyArray()

        for (n in all) {
            val caps = try {
                cm.getNetworkCapabilities(n)
            } catch (_: Throwable) {
                null
            } ?: continue
            val lp = try {
                cm.getLinkProperties(n)
            } catch (_: Throwable) {
                null
            } ?: continue

            result += toNetworkLink(n == activeId, caps, lp)
        }
        result
    } catch (_: Throwable) {
        emptyList()
    }

    private fun toNetworkLink(
        isActive: Boolean,
        caps: NetworkCapabilities,
        lp: LinkProperties
    ): NetworkLink {
        val transports = HashSet<Int>(4)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += NetworkCapabilities.TRANSPORT_ETHERNET
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += NetworkCapabilities.TRANSPORT_WIFI
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += NetworkCapabilities.TRANSPORT_CELLULAR
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += NetworkCapabilities.TRANSPORT_VPN
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) transports += NetworkCapabilities.TRANSPORT_USB

        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }

        return NetworkLink(
            interfaceName = lp.interfaceName,
            transports = transports,
            isActive = isActive,
            // 注意：这不是协商速率，官方定义是"估算的第一跳带宽"，以太网上常为 0。
            // UI 上必须标注"系统估算"。真正的链路速率只能靠 iperf3 实测。
            linkDownKbps = caps.linkDownstreamBandwidthKbps,
            linkUpKbps = caps.linkUpstreamBandwidthKbps,
            gateway = lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                ?: lp.routes.firstNotNullOfOrNull { it.gateway?.hostAddress },
            dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
            ipv4 = v4?.address?.hostAddress,
            prefixLength = v4?.prefixLength ?: 0
        )
    }

    fun transportName(t: Int): String = when (t) {
        NetworkCapabilities.TRANSPORT_ETHERNET -> "有线以太网"
        NetworkCapabilities.TRANSPORT_WIFI -> "Wi-Fi"
        NetworkCapabilities.TRANSPORT_CELLULAR -> "蜂窝"
        NetworkCapabilities.TRANSPORT_VPN -> "VPN"
        NetworkCapabilities.TRANSPORT_USB -> "USB"
        NetworkCapabilities.TRANSPORT_BLUETOOTH -> "蓝牙"
        else -> "其他($t)"
    }
}
