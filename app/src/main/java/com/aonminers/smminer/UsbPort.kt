package com.aonminers.smminer

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * FTDI FT230XQ USB serial port abstraction.
 */
class UsbPort(
    val device: UsbDevice,
    val info: DeviceInfo,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) {
    data class DeviceInfo(
        val manufacturer: String = "",
        val productName: String = "",
        val serialNumber: String = "",
        val port: Int = 0
    )

    @Volatile var isOpen = true
        private set

    /** FTDI RTS pin state: 0 = low, non-zero = high. */
    private var pinStatusRTS = 0

    // ── baud rate / config ──────────────────────────────────

    /** Set baud rate and serial format (8N1). Matches FTDI SIO_SET_BAUDRATE + SIO_SET_DATA. */
    fun setConfig(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int): Boolean {
        // FTDI baud rate divisor
        val divisor = (3_000_000 / baudRate).coerceIn(1, 0xFFFF)
        var ok = connection.controlTransfer(
            0x40, 3, divisor, 0, null, 0, 1000
        ) >= 0
        // Set data format: 8 bits, no parity, 1 stop bit = 0x0008
        val dataFormat = 0x0008
        ok = ok && connection.controlTransfer(
            0x40, 4, dataFormat, 0, null, 0, 1000
        ) >= 0
        // Enable flow control off, DTR/RTS low
        ok = ok && connection.controlTransfer(
            0x40, 0, 0, 0, null, 0, 1000
        ) >= 0
        return ok
    }

    // ── read / write ───────────────────────────────────────

    /** Read up to [maxLen] payload bytes with [timeoutMs].
     *  Strips the 2-byte FTDI modem/line-status header that the
     *  FT230XQ prepends to every bulk IN transfer. Returns empty array on error.
     *  */
    fun read(maxLen: Int, timeoutMs: Int): ByteArray {
        val buf = ByteArray(maxLen + 2)
        val n = connection.bulkTransfer(epIn, buf, maxLen + 2, timeoutMs)
        return if (n > 2) buf.copyOfRange(2, n) else ByteArray(0)
    }

    /** Write bytes. Returns number written or -1 on error. */
    fun write(data: ByteArray): Int =
        connection.bulkTransfer(epOut, data, data.size, 1000)

    // ── RTS / modem control ───────────────────────────────

    /** Toggle the FTDI RTS pin (HIGH↔LOW). Returns true on success*/
    fun toggleRTS(): Boolean {
        val wValue = if (pinStatusRTS == 0) {
            pinStatusRTS = 1
            0x0202  // mask 0x02 (RTS), value 0x02 → RTS HIGH
        } else {
            pinStatusRTS = 0
            0x0200  // mask 0x02 (RTS), value 0x00 → RTS LOW
        }
        return connection.controlTransfer(0x40, 1, wValue, 0, null, 0, 1000) >= 0
    }

    // ── latency timer ──────────────────────────────────────

    /** Set FTDI latency timer in milliseconds (1–255).
     *  Lower = less buffering delay, higher = fewer USB transfers.
     *  Default is 16ms; mining needs 1–2ms to hit the 10ms read timeout.
     *  */
    fun setLatency(ms: Int): Boolean =
        connection.controlTransfer(0x40, 0x09, ms, 0, null, 0, 1000) >= 0

    // ── lifecycle ──────────────────────────────────────────

    fun close() {
        isOpen = false
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    // ── static helpers ─────────────────────────────────────

    companion object {
        /** FTDI vendor/product IDs for AonMiner devices. */
        const val VID = 0x0403
        const val PID = 0x6015

        /**
         * Scan for connected FTDI devices and return open UsbPort
         * for each one whose product name matches [productFilter].
         * Logs diagnostic info via [log].
         */
        fun scan(context: Context, productFilter: String, log: (String) -> Unit = {}): List<UsbPort> {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            val ports = mutableListOf<UsbPort>()

            for ((name, device) in usbManager.deviceList) {
                if (device.vendorId != VID || device.productId != PID) continue
                if (device.interfaceCount == 0) continue

                val info = DeviceInfo(
                    manufacturer = device.manufacturerName ?: "",
                    productName = device.productName ?: "",
                    serialNumber = device.serialNumber ?: "",
                    port = 0
                )

                log("> USB device: $name — ${info.productName}")

                if (!info.productName.contains(productFilter, ignoreCase = true)) {
                    log(">  skipping (product filter: $productFilter)")
                    continue
                }

                // Check permission
                if (!usbManager.hasPermission(device)) {
                    log(">  no USB permission for this device")
                    continue
                }

                val iface = device.getInterface(0)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (i in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(i)
                    when (ep.direction) {
                        UsbConstants.USB_DIR_IN -> epIn = ep
                        UsbConstants.USB_DIR_OUT -> epOut = ep
                    }
                }
                if (epIn == null || epOut == null) {
                    log(">  missing endpoints — IN:${epIn != null} OUT:${epOut != null}")
                    continue
                }

                val conn = usbManager.openDevice(device)
                if (conn == null) {
                    log(">  openDevice failed — device may be in use, unplug and replug")
                    // Try to force-release
                    try {
                        val tmp = usbManager.openDevice(device)
                        tmp?.close()
                    } catch (_: Exception) {}
                    continue
                }

                if (!conn.claimInterface(iface, true)) {
                    log(">  claimInterface failed — interface already claimed")
                    conn.close()
                    continue
                }

                log(">  opened successfully (${info.productName})")
                ports.add(UsbPort(device, info, conn, iface, epIn, epOut))
            }
            return ports
        }

        /** Get raw UsbDevice list for permission request. */
        fun getDevices(context: Context): List<UsbDevice> {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return usbManager.deviceList.values.filter {
                it.vendorId == VID && it.productId == PID
            }
        }

        /** Force-release any stale interface claims on matching VID:PID devices. */
        fun releaseStale(context: Context) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            for (device in usbManager.deviceList.values) {
                if (device.vendorId != VID || device.productId != PID) continue
                if (device.interfaceCount == 0) continue
                try {
                    val conn = usbManager.openDevice(device)
                    if (conn != null) {
                        val iface = device.getInterface(0)
                        try { conn.releaseInterface(iface) } catch (_: Exception) {}
                        conn.close()
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
