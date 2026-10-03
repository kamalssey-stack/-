package com.cartune.pro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import java.io.IOException

/**
 * USB OBD2 Manager — подключается к ELM327 через USB OTG кабель.
 *
 * Схема подключения:
 *   Машина OBD2 порт  →  ELM327 USB адаптер  →  OTG кабель  →  Телефон
 *
 * Типичные адаптеры: VGATE iCar, Veepeak Mini, любой ELM327 USB
 */
class UsbOBD2Manager(private val context: Context) {

    companion object {
        private const val TAG = "UsbOBD2"
        private const val ACTION_USB_PERMISSION = "com.cartune.pro.USB_PERMISSION"
        private const val BAUD_RATE = 38400   // стандарт ELM327 USB
    }

    // ─── Данные ───────────────────────────────────────────────
    data class OBD2Data(
        val rpm: Int = 0,
        val speed: Int = 0,
        val coolantTemp: Int = 0,
        val throttlePos: Int = 0,
        val mafFlow: Float = 0f
    )

    interface Listener {
        fun onData(data: OBD2Data)
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onError(msg: String)
        fun onPermissionRequired()
    }

    private var listener: Listener? = null
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val recvBuffer = StringBuilder()
    private var isConnected = false

    fun setListener(l: Listener) { listener = l }

    // ─── USB Permission Receiver ──────────────────────────────
    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            if (granted && device != null) {
                openPort(device)
            } else {
                listener?.onError("USB разрешение отклонено")
            }
        }
    }

    // ─── Публичный API ────────────────────────────────────────

    /**
     * Найти и подключиться к первому ELM327 USB устройству.
     * Запросит разрешение если нужно.
     */
    fun connectFirstAvailable() {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            listener?.onError("USB OBD2 адаптер не найден. Подключи ELM327 через OTG кабель.")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        // Регистрируем receiver для разрешения
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            context.registerReceiver(permReceiver, filter)

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
        } else {
            openPort(device)
        }
    }

    private fun openPort(device: UsbDevice) {
        scope.launch {
            try {
                val usbManager = context.getSystemService(UsbManager::class.java)
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: throw IOException("Нет драйвера для ${device.deviceName}")

                val connection = usbManager.openDevice(device)
                    ?: throw IOException("Не удалось открыть USB устройство")

                val port = driver.ports[0]
                port.open(connection)
                port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                port.rts = true

                serialPort = port
                isConnected = true

                Log.d(TAG, "Opened port: ${device.deviceName}")

                // Инициализация ELM327
                initELM327()

                withContext(Dispatchers.Main) {
                    listener?.onConnected(
                        device.productName ?: device.deviceName ?: "USB OBD2"
                    )
                }

                startPolling()

            } catch (e: Exception) {
                Log.e(TAG, "openPort failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    listener?.onError("Ошибка подключения: ${e.message}")
                }
            }
        }
    }

    // ─── ELM327 Инициализация ─────────────────────────────────

    private suspend fun initELM327() {
        delay(500)
        sendCmd("ATZ\r");  delay(1500)  // Сброс
        sendCmd("ATE0\r"); delay(300)   // Echo off
        sendCmd("ATL0\r"); delay(300)   // Line feeds off
        sendCmd("ATH0\r"); delay(300)   // Headers off
        sendCmd("ATSP0\r"); delay(500)  // Auto protocol
        sendCmd("ATAT1\r"); delay(300)  // Adaptive timing
        Log.d(TAG, "ELM327 init done")
    }

    // ─── Polling Loop ─────────────────────────────────────────

    private fun startPolling() {
        pollingJob = scope.launch {
            while (isConnected && isActive) {
                try {
                    val rpm      = readRPM()
                    val speed    = readSpeed()
                    val throttle = readThrottle()
                    val coolant  = readCoolant()
                    val maf      = readMAF()

                    val data = OBD2Data(rpm, speed, coolant, throttle, maf)
                    withContext(Dispatchers.Main) { listener?.onData(data) }

                    delay(80)  // ~12 Hz
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                    delay(300)
                }
            }
        }
    }

    // ─── PID Readers ──────────────────────────────────────────

    private fun readRPM(): Int {
        val r = sendCmd("010C\r") ?: return 0
        return parseBytes(r, 2)?.let { (a, b) -> ((a * 256) + b) / 4 } ?: 0
    }

    private fun readSpeed(): Int {
        val r = sendCmd("010D\r") ?: return 0
        return parseBytes(r, 1)?.let { (a, _) -> a } ?: 0
    }

    private fun readThrottle(): Int {
        val r = sendCmd("0111\r") ?: return 0
        return parseBytes(r, 1)?.let { (a, _) -> a * 100 / 255 } ?: 0
    }

    private fun readCoolant(): Int {
        val r = sendCmd("0105\r") ?: return 0
        return parseBytes(r, 1)?.let { (a, _) -> a - 40 } ?: 0
    }

    private fun readMAF(): Float {
        val r = sendCmd("0110\r") ?: return 0f
        return parseBytes(r, 2)?.let { (a, b) -> ((a * 256) + b) / 100f } ?: 0f
    }

    // ─── Serial I/O ───────────────────────────────────────────

    private fun sendCmd(cmd: String): String? {
        val port = serialPort ?: return null
        return try {
            port.write(cmd.toByteArray(), 500)
            readResponse()
        } catch (e: Exception) {
            Log.w(TAG, "Write error: ${e.message}")
            null
        }
    }

    private fun readResponse(): String {
        val port = serialPort ?: return ""
        val buf = ByteArray(256)
        val sb = StringBuilder()
        val timeout = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < timeout) {
            val n = try {
                port.read(buf, 50)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) {
                if (n == -1) break
                Thread.sleep(5)
                continue
            }
            val chunk = String(buf, 0, n)
            sb.append(chunk)
            if (chunk.contains('>')) break  // ELM327 prompt
        }
        return sb.toString().trim().replace(">", "").trim()
    }

    private fun parseBytes(response: String, count: Int): Pair<Int, Int>? {
        return try {
            val hex   = response.replace(Regex("[^0-9A-Fa-f ]"), "").trim()
            val parts = hex.split(" ").filter { it.length == 2 && it.all { c -> c.isLetterOrDigit() } }
            if (parts.size >= 2 + count) {
                Pair(parts[2].toInt(16), if (count >= 2 && parts.size >= 4) parts[3].toInt(16) else 0)
            } else null
        } catch (e: Exception) { null }
    }

    // ─── Lifecycle ────────────────────────────────────────────

    fun disconnect() {
        isConnected = false
        pollingJob?.cancel()
        try { serialPort?.close() } catch (e: Exception) {}
        serialPort = null
        try { context.unregisterReceiver(permReceiver) } catch (e: Exception) {}
        scope.launch(Dispatchers.Main) { listener?.onDisconnected() }
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
