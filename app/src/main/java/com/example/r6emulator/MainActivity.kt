package com.example.r6emulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var bikeRpmText: TextView
    private lateinit var r6RpmText: TextView
    private lateinit var connectBtn: Button
    private lateinit var soundBtn: Button
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var filterSwitch: Switch
    private lateinit var simRpmSeekBar: SeekBar
    private lateinit var simRpmText: TextView

    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false
    private var isSoundActive = false
    private var isSimulateMode = false

    private lateinit var audioEngine: MultiSampleCrossfadeEngine
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        bikeRpmText = findViewById(R.id.bikeRpmText)
        r6RpmText = findViewById(R.id.r6RpmText)
        connectBtn = findViewById(R.id.connectBtn)
        soundBtn = findViewById(R.id.soundBtn)

        filterSwitch = findViewById(R.id.filterSwitch)
        simRpmSeekBar = findViewById(R.id.simRpmSeekBar)
        simRpmText = findViewById(R.id.simRpmText)

        audioEngine = MultiSampleCrossfadeEngine(this)

        checkPermissions()

        connectBtn.setOnClickListener {
            if (!isConnected) connectObd() else disconnectObd()
        }

        soundBtn.setOnClickListener {
            isSoundActive = !isSoundActive
            if (isSoundActive) {
                audioEngine.start()
                soundBtn.text = "Stop Sound"
            } else {
                audioEngine.stop()
                soundBtn.text = "Start Sound"
            }
        }

        // Toggle Stock Exhaust Muffling DSP Filter
        filterSwitch.setOnCheckedChangeListener { _, isChecked ->
            audioEngine.setFilterEnabled(isChecked)
            Toast.makeText(this, if (isChecked) "Stock Muffler DSP Active" else "Raw Sound Direct", Toast.LENGTH_SHORT).show()
        }

        // Engine RPM Simulator (Test sound without OBD2 connection)
        simRpmSeekBar.max = 9500
        simRpmSeekBar.progress = 1400
        simRpmSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isSimulateMode = true
                    val simulatedRpm = progress.toDouble()
                    simRpmText.text = "Simulated RPM: $progress"
                    bikeRpmText.text = "Bike RPM: $progress (Simulated)"
                    r6RpmText.text = "Yamaha R6 RPM: $progress"

                    if (isSoundActive) {
                        audioEngine.setRpm(simulatedRpm)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

    @SuppressLint("MissingPermission")
    private fun connectObd() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        val obdDevice = pairedDevices.find {
            val name = it.name ?: ""
            name.contains("OBD", ignoreCase = true) || name.contains("ELM", ignoreCase = true) || name.contains("V-LINK", ignoreCase = true)
        }

        if (obdDevice == null) {
            statusText.text = "Status: OBD2 device not found in paired list."
            return
        }

        statusText.text = "Status: Connecting to ${obdDevice.name}..."

        Thread {
            try {
                bluetoothSocket = obdDevice.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket?.connect()
                isConnected = true
                isSimulateMode = false

                mainHandler.post {
                    statusText.text = "Status: Connected to ${obdDevice.name}"
                    connectBtn.text = "Disconnect OBD2"
                }

                readRpmLoop(bluetoothSocket?.inputStream, bluetoothSocket?.outputStream)
            } catch (e: Exception) {
                isConnected = false
                mainHandler.post { statusText.text = "Status: Connection Failed - ${e.localizedMessage}" }
            }
        }.start()
    }

    private fun readRpmLoop(inputStream: InputStream?, outputStream: OutputStream?) {
        if (inputStream == null || outputStream == null) return

        try {
            outputStream.write("ATZ\r".toByteArray())
            Thread.sleep(200)
            outputStream.write("ATL0\rATH0\rATS0\rATAT2\r".toByteArray())
            Thread.sleep(200)
        } catch (e: Exception) {}

        val buffer = ByteArray(128)
        while (isConnected) {
            try {
                outputStream.write("010C1\r".toByteArray())
                outputStream.flush()
                Thread.sleep(15)

                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    val rpm = parseRpmResponse(response)

                    if (rpm >= 0 && !isSimulateMode) {
                        mainHandler.post {
                            bikeRpmText.text = "Bike RPM: ${rpm.toInt()}"
                            r6RpmText.text = "Yamaha R6 RPM: ${rpm.toInt()}"
                            simRpmSeekBar.progress = rpm.toInt().coerceIn(0, 9500)
                            simRpmText.text = "Simulated RPM: ${rpm.toInt()}"
                        }

                        if (isSoundActive) {
                            audioEngine.setRpm(rpm)
                        }
                    }
                }
            } catch (e: Exception) {
                isConnected = false
                mainHandler.post {
                    statusText.text = "Status: OBD Connection Lost"
                    connectBtn.text = "Connect OBD2"
                }
                break
            }
        }
    }

    private fun parseRpmResponse(rawResponse: String): Double {
        val clean = rawResponse.replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
        val index = clean.indexOf("410C")
        if (index != -1 && clean.length >= index + 8) {
            try {
                val hexA = clean.substring(index + 4, index + 6)
                val hexB = clean.substring(index + 6, index + 8)
                return ((hexA.toInt(16) * 256) + hexB.toInt(16)) / 4.0
            } catch (e: Exception) {
                return -1.0
            }
        }
        return -1.0
    }

    @SuppressLint("MissingPermission")
    private fun disconnectObd() {
        isConnected = false
        try { bluetoothSocket?.close() } catch (e: Exception) {}
        statusText.text = "Status: Disconnected"
        connectBtn.text = "Connect OBD2"
        bikeRpmText.text = "Bike RPM: 0"
        r6RpmText.text = "Yamaha R6 RPM: 0"
        audioEngine.setRpm(0.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectObd()
        audioEngine.stop()
    }
}

/**
 * Audio Engine with Dynamic Crossfading and Real-Time Acoustic DSP Filters
 */
class MultiSampleCrossfadeEngine(private val context: Context) {
    private var isRunning = false
    private var audioTrack: AudioTrack? = null

    private var sample1400: ShortArray = ShortArray(0)
    private var sample4000: ShortArray = ShortArray(0)
    private var sample6000: ShortArray = ShortArray(0)
    private var sample8000: ShortArray = ShortArray(0)

    @Volatile private var targetRpm = 0.0
    private var currentRpm = 0.0
    @Volatile private var isFilterEnabled = true

    // Real-Time Biquad DSP Filters
    private val warmthEq = BiquadFilter()
    private val sweetnessDip = BiquadFilter()
    private val lpf1 = BiquadFilter()
    private val lpf2 = BiquadFilter()

    init {
        // Load raw WAV files with 0.10s edge trim applied to eliminate loop clicks
        val trimMarginSeconds = 0.10
        sample1400 = loadWavResourceTrimmed("r6_1400", trimMarginSeconds)
        sample4000 = loadWavResourceTrimmed("r6_4000", trimMarginSeconds)
        sample6000 = loadWavResourceTrimmed("r6_6000", trimMarginSeconds)
        sample8000 = loadWavResourceTrimmed("r6_8000", trimMarginSeconds)
    }

    fun setFilterEnabled(enabled: Boolean) {
        isFilterEnabled = enabled
    }

    private fun loadWavResourceTrimmed(resName: String, trimSeconds: Double): ShortArray {
        try {
            val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
            if (resId != 0) {
                val inputStream = context.resources.openRawResource(resId)
                val bytes = inputStream.readBytes()
                inputStream.close()

                val pcmBytes = bytes.copyOfRange(44, bytes.size)
                val totalShorts = pcmBytes.size / 2
                val fullArray = ShortArray(totalShorts)
                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(fullArray)

                // 0.10s edge trim calculation (44100 samples/sec * 0.10s)
                val trimSamples = (44100 * trimSeconds).toInt()
                val startIndex = trimSamples.coerceAtMost(fullArray.size / 2)
                val endIndex = (fullArray.size - trimSamples).coerceAtLeast(startIndex)

                return fullArray.copyOfRange(startIndex, endIndex)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ShortArray(0)
    }

    fun start() {
        if (isRunning || sample1400.isEmpty()) return
        val sampleRate = 44100
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        audioTrack = builder.build()
        audioTrack?.play()
        isRunning = true

        Thread {
            var ptr1400 = 0.0
            var ptr4000 = 0.0
            var ptr6000 = 0.0
            var ptr8000 = 0.0

            val writeBuffer = ShortArray(512)

            while (isRunning) {
                currentRpm += (targetRpm - currentRpm) * 0.25

                if (currentRpm < 300.0) {
                    writeBuffer.fill(0)
                } else {
                    val speed1400 = currentRpm / 1400.0
                    val speed4000 = currentRpm / 4000.0
                    val speed6000 = currentRpm / 6000.0
                    val speed8000 = currentRpm / 8000.0

                    val (w1, w2, w3, w4) = calculateWeights(currentRpm)

                    // Configure DSP Filters (Softness = 1600Hz LPF base, Sweetness = -5dB @ 1.4kHz, Warmth = +4dB @ 320Hz)
                    val baseCutoff = 1600.0
                    val dynamicCutoff = (baseCutoff + (currentRpm * 0.22)).coerceAtMost(12000.0)

                    warmthEq.setPeakingEq(44100.0, 320.0, 4.0, 1.0)
                    sweetnessDip.setPeakingEq(44100.0, 1400.0, -5.0, 1.4)
                    lpf1.setLowPass(44100.0, dynamicCutoff, 0.707)
                    lpf2.setLowPass(44100.0, dynamicCutoff, 0.707)

                    for (i in writeBuffer.indices) {
                        val s1 = getInterpolatedSample(sample1400, ptr1400)
                        val s2 = getInterpolatedSample(sample4000, ptr4000)
                        val s3 = getInterpolatedSample(sample6000, ptr6000)
                        val s4 = getInterpolatedSample(sample8000, ptr8000)

                        var blended = (s1 * w1) + (s2 * w2) + (s3 * w3) + (s4 * w4)

                        // Process through Stock Exhaust DSP Filter Chain
                        if (isFilterEnabled) {
                            blended = warmthEq.process(blended)
                            blended = sweetnessDip.process(blended)
                            blended = lpf1.process(blended)
                            blended = lpf2.process(blended)
                        }

                        writeBuffer[i] = blended.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                        if (sample1400.isNotEmpty()) ptr1400 = (ptr1400 + speed1400) % sample1400.size
                        if (sample4000.isNotEmpty()) ptr4000 = (ptr4000 + speed4000) % sample4000.size
                        if (sample6000.isNotEmpty()) ptr6000 = (ptr6000 + speed6000) % sample6000.size
                        if (sample8000.isNotEmpty()) ptr8000 = (ptr8000 + speed8000) % sample8000.size
                    }
                }
                audioTrack?.write(writeBuffer, 0, writeBuffer.size)
            }
        }.start()
    }

    private fun calculateWeights(rpm: Double): FloatArray {
        var w1 = 0.0f; var w2 = 0.0f; var w3 = 0.0f; var w4 = 0.0f
        when {
            rpm <= 1400.0 -> w1 = 1.0f
            rpm in 1400.0..4000.0 -> {
                val t = ((rpm - 1400.0) / (4000.0 - 1400.0)).toFloat()
                w1 = 1.0f - t; w2 = t
            }
            rpm in 4000.0..6000.0 -> {
                val t = ((rpm - 4000.0) / (6000.0 - 4000.0)).toFloat()
                w2 = 1.0f - t; w3 = t
            }
            rpm in 6000.0..8000.0 -> {
                val t = ((rpm - 6000.0) / (8000.0 - 6000.0)).toFloat()
                w3 = 1.0f - t; w4 = t
            }
            else -> w4 = 1.0f
        }
        return floatArrayOf(w1, w2, w3, w4)
    }

    private fun getInterpolatedSample(buffer: ShortArray, pointer: Double): Double {
        if (buffer.isEmpty()) return 0.0
        val idx0 = pointer.toInt() % buffer.size
        val idx1 = (idx0 + 1) % buffer.size
        val frac = pointer - pointer.toInt()
        return buffer[idx0] + frac * (buffer[idx1] - buffer[idx0])
    }

    fun setRpm(r6Rpm: Double) {
        targetRpm = r6Rpm
    }

    fun stop() {
        isRunning = false
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }
}

/**
 * High-Efficiency Real-Time Biquad DSP Filter Implementation
 */
class BiquadFilter {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun setLowPass(sampleRate: Double, cutoffFreq: Double, q: Double) {
        val w0 = 2.0 * Math.PI * cutoffFreq / sampleRate
        val cosW0 = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * q)

        val a0Temp = 1.0 + alpha
        b0 = ((1.0 - cosW0) / 2.0) / a0Temp
        b1 = (1.0 - cosW0) / a0Temp
        b2 = ((1.0 - cosW0) / 2.0) / a0Temp
        a1 = (-2.0 * cosW0) / a0Temp
        a2 = (1.0 - alpha) / a0Temp
    }

    fun setPeakingEq(sampleRate: Double, centerFreq: Double, gainDb: Double, q: Double) {
        val w0 = 2.0 * Math.PI * centerFreq / sampleRate
        val cosW0 = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * q)
        val aVal = Math.pow(10.0, gainDb / 40.0)

        val a0Temp = 1.0 + alpha / aVal
        b0 = (1.0 + alpha * aVal) / a0Temp
        b1 = (-2.0 * cosW0) / a0Temp
        b2 = (1.0 - alpha * aVal) / a0Temp
        a1 = (-2.0 * cosW0) / a0Temp
        a2 = (1.0 - alpha / aVal) / a0Temp
    }

    fun process(sample: Double): Double {
        val y = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = y
        return y
    }
}
