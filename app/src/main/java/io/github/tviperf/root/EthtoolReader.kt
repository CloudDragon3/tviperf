package io.github.tviperf.root

import io.github.tviperf.util.Fmt

/**
 * root 增强模式：读 sysfs / ethtool 拿到免 root 下拿不到的三项硬件事实。
 *
 * 只有在电视已 root 时才有内容。未 root 时返回的结果是"不可用"，
 * 不做任何报错、不阻塞 UI。
 *
 * 为什么免 root 读不到：/sys/class/net 下（含 speed / duplex / device/uevent）在 SELinux 里
 * 被标记为 sysfs_net 类型，
 * 第三方应用所在的 untrusted_app 域没有被授予该类型的读权限（Android 6 起收紧、
 * Android 10 进一步禁止读 MAC）。ethtool 则需要 CAP_NET_ADMIN。这不是 bug，是策略。
 */
object EthtoolReader {

    data class RootLinkInfo(
        val rootAvailable: Boolean,
        val iface: String?,
        val negotiatedSpeed: String?,
        val duplex: String?,
        val driverName: String?,
        val driverVersion: String?,
        val firmwareVersion: String?,
        val busInfo: String?,
        val usbSpeed: String?,
        val rawLog: String
    ) {
        val hasAny: Boolean
            get() = negotiatedSpeed != null || driverName != null || usbSpeed != null
    }

    fun read(iface: String?): RootLinkInfo {
        if (!RootCapability.isRootAvailable()) {
            return RootLinkInfo(
                rootAvailable = false,
                iface = iface,
                negotiatedSpeed = null, duplex = null,
                driverName = null, driverVersion = null, firmwareVersion = null,
                busInfo = null, usbSpeed = null,
                rawLog = "未检测到 root（su 不可用或授权超时）。协商速率、驱动版本、USB 链路速率在免 root 下无法读取 —— 这是 Android 的 SELinux 策略，不是应用缺陷。"
            )
        }

        val log = StringBuilder()
        val name = iface ?: ""

        // 1) 协商速率 / 双工
        var speed: String? = null
        var duplex: String? = null
        if (name.isNotEmpty()) {
            val rawSpeed = RootCapability.runAsRoot("cat /sys/class/net/$name/speed")
            log.append("[speed] ").append(rawSpeed ?: "读取失败").append('\n')
            speed = rawSpeed?.takeIf { it.matches(Regex("\\d+")) }?.let { "$it Mb/s" }

            val rawDuplex = RootCapability.runAsRoot("cat /sys/class/net/$name/duplex")
            log.append("[duplex] ").append(rawDuplex ?: "读取失败").append('\n')
            duplex = rawDuplex?.takeIf { it.isNotBlank() }
        }

        // 2) 驱动名称与版本
        var drvName: String? = null
        var drvVersion: String? = null
        var fwVersion: String? = null
        var busInfo: String? = null

        if (name.isNotEmpty()) {
            val ethtoolI = RootCapability.runAsRoot("ethtool -i $name")
            log.append("[ethtool -i] ").append(ethtoolI ?: "不可用").append('\n')
            if (ethtoolI != null) {
                drvName = field(ethtoolI, "driver")
                drvVersion = field(ethtoolI, "version")
                fwVersion = field(ethtoolI, "firmware-version")
                busInfo = field(ethtoolI, "bus-info")
            }

            if (drvName == null) {
                val uevent = RootCapability.runAsRoot("cat /sys/class/net/$name/device/uevent")
                log.append("[uevent] ").append(uevent ?: "读取失败").append('\n')
                drvName = field(uevent.orEmpty(), "DRIVER")
            }
            if (drvName != null && drvVersion == null) {
                drvVersion = RootCapability.runAsRoot("cat /sys/module/$drvName/version")
                log.append("[module version] ").append(drvVersion ?: "读取失败").append('\n')
            }
        }

        // 3) USB 实际链路速率：5000 = USB 3.0，480 = USB 2.0
        val usbRaw = RootCapability.runAsRoot("cat /sys/bus/usb/devices/*/speed")
        log.append("[usb speed] ").append(usbRaw ?: "读取失败").append('\n')
        val usbSpeed = usbRaw
            ?.lines()
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it == 480 || it == 5000 || it == 10000 || it == 20000 }
            ?.maxOrNull()
            ?.let { "USB ${Fmt.oneDecimal(it / 1000.0)} Gb/s" + if (it == 480) "（USB 2.0，会严重限制吞吐）" else "" }

        return RootLinkInfo(
            rootAvailable = true,
            iface = name,
            negotiatedSpeed = speed,
            duplex = duplex,
            driverName = drvName,
            driverVersion = drvVersion,
            firmwareVersion = fwVersion,
            busInfo = busInfo,
            usbSpeed = usbSpeed,
            rawLog = log.toString()
        )
    }

    private fun field(text: String, key: String): String? =
        text.lines()
            .firstOrNull { it.trim().startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** root 信息 → 面板展示文本 */
    fun format(info: RootLinkInfo): String {
        if (!info.rootAvailable) return info.rawLog
        val sb = StringBuilder()
        sb.append("协商速率：").append(info.negotiatedSpeed ?: "不可用").append('\n')
        sb.append("双工：").append(info.duplex ?: "不可用").append('\n')
        sb.append("驱动：").append(info.driverName ?: "不可用")
        info.driverVersion?.let { sb.append(" v").append(it) }
        sb.append('\n')
        sb.append("固件：").append(info.firmwareVersion ?: "不可用").append('\n')
        sb.append("总线：").append(info.busInfo ?: "不可用").append('\n')
        sb.append("USB 链路：").append(info.usbSpeed ?: "不可用").append('\n')
        sb.append("\n—— 原始输出 ——\n").append(info.rawLog)
        return sb.toString()
    }
}
