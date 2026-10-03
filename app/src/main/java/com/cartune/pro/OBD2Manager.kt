package com.cartune.pro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * OBD2 Manager — connects to ELM327 Bluetooth adapter and reads live PIDs.
 * Usage:
 *   obd2.connect(device)
 *   obd2.setListener { rpm, speed, temp, throttle -> ... }
 *   obd2.disconnect()
 */
class OBD2Manager(private val context: Context) {

    companion object {
        private const val TAG = "OBD2Manager"
        // Standard SPP UUID for ELM327
        private val OBD_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // OBD2 PIDs
        private const val PID_RPM      = "010C\r"   // Engine RPM
        private const val PID_SPEED    = "010D\r"   // Vehicle speed km/h
        private const val PID_COOLANT  = "0105\r"   // Coolant temp
        private const val PID_THROTTLE = "0111\r"   // Throttle position %
        private const val PID_MAF      = "0110\r"   // MAF air flow g/s
        private const val CMD_INIT     = "ATZ\r"    // Reset ELM327
        private const val CMD_ECHO_OFF = "ATE0\r"   // Echo off
        private const val CMD_LINEFEEDS= "ATL0\r"   // Line feeds off
        private const val CMD_HEADERS  = "ATH0\r"   // Headers off
        private const val CMD_PROTO    = "ATSP0\r"  // Auto protocol
    }

    data class OBD2Data(
        val rpm: Int = 0,
        val speed: Int = 0,
        val coolantTemp: Int = 0,
        val throttlePos: Int = 0,
        val mafFlow: Float = 0f,
        val connected: Boolean = false
    )

    interface OBD2Listener {
        fun onData(data: OBD2Data)
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onError(message: String)
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var listener: OBD2Listener? = null
    private var pollingJob: Job? = null
    private var isConnected = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setListener(l: OBD2Listener) { listener = l }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        scope.launch {
            try {
                socket?.close()
                Log.d(TAG, "Connecting to ${device.name}…")
                val s = device.createRfcommSocketToServiceRecord(OBD_UUID)
                s.connect()
                socket = s
                inputStream  = s.inputStream
                outputStream = s.outputStream
                isConnected  = true

                // Initialize ELM327
                initELM327()

                withContext(Dispatchers.Main) {
                    listener?.onConnected(device.name ?: "OBD2 Device")
                }
                startPolling()
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    listener?.onError("Не удалось подключиться: ${e.message}")
                }
            }
        }
    }

    private suspend fun initELM327() {
        delay(1000)
        sendCmd(CMD_INIT);   delay(1500)
        sendCmd(CMD_ECHO_OFF); delay(300)
        sendCmd(CMD_LINEFEEDS);delay(300)
        sendCmd(CMD_HEADERS);  delay(300)
        sendCmd(CMD_PROTO);    delay(500)
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var rpm = 0; var speed = 0; var coolant = 0; var throttle = 0; var maf = 0f
            while (isConnected && isActive) {
                try {
                    // Read each PID in rotation
                    rpm      = readRPM()
                    speed    = readSpeed()
                    throttle = readThrottle()
                    coolant  = readCoolant()
                    maf      = readMAF()

                    val data = OBD2Data(rpm, speed, coolant, throttle, maf, true)
                    withContext(Dispatchers.Main) { listener?.onData(data) }
                    delay(100) // ~10 Hz polling
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                    if (!isConnected) break
                    delay(500)
                }
            }
        }
    }

    // ─── PID Readers ───────────────────────────────────────────────

    private fun readRPM(): Int {
        val resp = sendCmd(PID_RPM) ?: return 0
        // Response: "41 0C XX YY" → RPM = ((A*256)+B)/4
        return parseObdBytes(resp, 2)?.let { (a, b) -> ((a * 256) + b) / 4 } ?: 0
    }

    private fun readSpeed(): Int {
        val resp = sendCmd(PID_SPEED) ?: return 0
        // Response: "41 0D XX" → speed = A
        return parseObdBytes(resp, 1)?.let { (a, _) -> a } ?: 0
    }

    private fun readCoolant(): Int {
        val resp = sendCmd(PID_COOLANT) ?: return 0
        // Response: "41 05 XX" → temp = A - 40
        return parseObdBytes(resp, 1)?.let { (a, _) -> a - 40 } ?: 0
    }

    private fun readThrottle(): Int {
        val resp = sendCmd(PID_THROTTLE) ?: return 0
        // Response: "41 11 XX" → throttle = A * 100 / 255
        return parseObdBytes(resp, 1)?.let { (a, _) -> a * 100 / 255 } ?: 0
    }

    private fun readMAF(): Float {
        val resp = sendCmd(PID_MAF) ?: return 0f
        // Response: "41 10 XX YY" → MAF = ((A*256)+B)/100 g/s
        return parseObdBytes(resp, 2)?.let { (a, b) -> ((a * 256) + b) / 100f } ?: 0f
    }

    // ─── Low-level I/O ─────────────────────────────────────────────

    private fun sendCmd(cmd: String): String? {
        return try {
            outputStream?.write(cmd.toByteArray())
            outputStream?.flush()
            readResponse()
        } catch (e: IOException) {
            Log.e(TAG, "Send error: ${e.message}")
            null
        }
    }

    private fun readResponse(): String {
        val buf = StringBuilder()
        val input = inputStream ?: return ""
        val timeout = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < timeout) {
            if (input.available() > 0) {
                val byte = input.read()
                val ch = byte.toChar()
                if (ch == '>') break   // ELM327 prompt = done
                buf.append(ch)
            } else {
                Thread.sleep(5)
            }
        }
        return buf.toString().trim()
    }

    private fun parseObdBytes(response: String, count: Int): Pair<Int, Int>? {
        return try {
            // Strip any non-hex characters, split into bytes
            val hex = response.replace(Regex("[^0-9A-Fa-f ]"), "").trim()
            val parts = hex.split(" ").filter { it.length == 2 }
            // Skip "41 XX" header (2 bytes), take data bytes
            if (parts.size >= 2 + count) {
                val a = parts[2].toInt(16)
                val b = if (count >= 2 && parts.size >= 4) parts[3].toInt(16) else 0
                Pair(a, b)
            } else null
        } catch (e: Exception) { null }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────

    fun disconnect() {
        isConnected = false
        pollingJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        scope.launch(Dispatchers.Main) { listener?.onDisconnected() }
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    fun getPairedOBD2Devices(): List<BluetoothDevice> {
        return try {
            val bm = context.getSystemService(BluetoothManager::class.java)
            val adapter: BluetoothAdapter = bm?.adapter ?: return emptyList()
            @SuppressLint("MissingPermission")
            val paired = adapter.bondedDevices ?: return emptyList()
            // Filter likely OBD2 devices by name
            paired.filter { d ->
                val name = try { d.name?.uppercase() ?: "" } catch (_: SecurityException) { "" }
                name.contains("ELM") || name.contains("OBD") ||
                name.contains("OBDII") || name.contains("VLINK") ||
                name.contains("VEEPEAK") || name.contains("KONNWEI")
            }
        } catch (_: Exception) { emptyList() }
    }
}
