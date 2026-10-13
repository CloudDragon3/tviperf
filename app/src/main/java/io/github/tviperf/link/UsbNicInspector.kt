package io.github.tviperf.link

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** USB 网卡描述符读到的内容。全部是"实测值"，无需 USB 授权。 */
data class UsbNicInfo(
    val vendorId: Int,
    val productId: Int,
    val vidPid: String,
    /** 这是 bcdDevice（设备/固件版本号），不是 USB 协议版本，也不是驱动版本 */
    val deviceVersionBcd: String,
    val interfaceClasses: List<Int>,
    val chipGuess: String?,
    val cdcLike: Boolean,
    val vendorSpecific: Boolean
)

/**
 * 只读 USB 描述符。
 *
 * ★★★ 硬性纪律 ★★★
 * 本类**只允许**调用 `UsbManager.getDeviceList()`。
 * **绝对禁止** `openDevice()` / `claimInterface()`。
 *
 * 原因：网卡接口已被内核驱动接管。应用一旦尝试打开或抢占该接口，
 * 轻则返回失败，重则会把正在使用的网络接口搞断 —— 那等于把自己的测试链路亲手打断，
 * 而且失败现象极难排查。厂商名/产品名字符串需要 USB 授权（也就是需要 openDevice），
 * 所以本项目直接放弃这两个字段，改用「VID:PID + MAC OUI + 接口类 + 接口名」替代，
 * 信息量已经足够，且是零风险的纯只读路径。
 */
object UsbNicInspector {

    private data class Known(val label: String, val kind: String)

    /** 已知 USB 网卡芯片表（VID:PID 为免 root 下最可靠的型号来源） */
    private val KNOWN: Map<Int, Map<Int, Known>> = mapOf(
        0x0BDA to mapOf( // Realtek
            0x8152 to Known("Realtek RTL8152 (百兆)", "NIC"),
            0x8153 to Known("Realtek RTL8153 (千兆)", "NIC"),
            0x8156 to Known("Realtek RTL8156 / 8156B / 8156BG (2.5G)", "NIC"),
            0x8157 to Known("Realtek RTL8157 (5G)", "NIC"),
            0x8125 to Known("Realtek RTL8125 (2.5G PCIe)", "NIC")
        ),
        0x0B95 to mapOf(
            0x1790 to Known("ASIX AX88179 (千兆)", "NIC"),
            0x772B to Known("ASIX AX88772B (百兆)", "NIC")
        )
    )

    private const val CLASS_CDC_CONTROL = 0x02
    private const val CLASS_CDC_DATA = 0x0A
    private const val CLASS_HID = 0x03
    private const val CLASS_MASS_STORAGE = 0x08
    private const val CLASS_VENDOR_SPECIFIC = 0xFF

    fun inspect(ctx: Context): List<UsbNicInfo> {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            um.deviceList.values.mapNotNull { toInfo(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 挑出"最像外接网卡"的那一个。
     * 优先：已知网卡 VID:PID > 有 CDC/厂商自定义接口 > 任意设备。
     * 只插了一个 USB 网卡的场景下这一步几乎不会出错。
     */
    fun primaryNic(list: List<UsbNicInfo>): UsbNicInfo? {
        if (list.isEmpty()) return null
        return list.firstOrNull { it.chipGuess != null }
            ?: list.firstOrNull { it.cdcLike || it.vendorSpecific }
            ?: list.firstOrNull()
    }

    private fun toInfo(d: UsbDevice): UsbNicInfo {
        val classes = try {
            (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }
        } catch (_: Throwable) {
            emptyList()
        }

        val vid = d.vendorId
        val pid = d.productId
        val known = KNOWN[vid]?.get(pid)

        val cdcLike = classes.contains(CLASS_CDC_CONTROL) && classes.contains(CLASS_CDC_DATA)
        // 厂商自定义接口：设备走厂商专用路径，系统更可能挂原厂驱动
        val vendorSpecific = classes.contains(CLASS_VENDOR_SPECIFIC)

        val guess = known?.label ?: guessByClasses(classes)

        return UsbNicInfo(
            vendorId = vid,
            productId = pid,
            vidPid = "%04x:%04x".format(vid, pid),
            deviceVersionBcd = "0x%04x".format(d.version),
            interfaceClasses = classes,
            chipGuess = guess,
            cdcLike = cdcLike,
            vendorSpecific = vendorSpecific
        )
    }

    private fun guessByClasses(classes: List<Int>): String? = when {
        classes.isEmpty() -> null
        classes.all { it == CLASS_HID } -> "人机接口设备（不是网卡）"
        classes.contains(CLASS_MASS_STORAGE) -> "大容量存储（不是网卡）"
        classes.contains(CLASS_CDC_DATA) -> "未在芯片表里的 CDC 网卡"
        classes.contains(CLASS_VENDOR_SPECIFIC) -> "未在芯片表里的厂商自定义网卡"
        else -> null
    }

    /** 接口类的中文解释，直接显示在面板上 */
    fun describeClasses(classes: List<Int>): String {
        if (classes.isEmpty()) return "读不到"
        return classes.joinToString(" + ") { c ->
            when (c) {
                CLASS_CDC_CONTROL -> "0x02(CDC控制)"
                CLASS_CDC_DATA -> "0x0A(CDC数据)"
                CLASS_VENDOR_SPECIFIC -> "0xFF(厂商自定义)"
                CLASS_HID -> "0x03(HID)"
                CLASS_MASS_STORAGE -> "0x08(存储)"
                else -> "0x%02X".format(c)
            }
        }
    }
}
