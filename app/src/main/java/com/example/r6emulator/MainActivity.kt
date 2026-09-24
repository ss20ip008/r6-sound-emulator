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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var bikeRpmText: TextView
    private lateinit var r6RpmText: TextView
    private lateinit var connectBtn: Button
    private lateinit var soundBtn: Button

    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false
    private var isSoundActive = false
    private val audioSynth = EngineAudioSynth()

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

        checkPermissions()

        connectBtn.setOnClickListener {
            if (!isConnected) connectObd() else disconnectObd()
        }

        soundBtn.setOnClickListener {
            isSoundActive = !isSoundActive
            if (isSoundActive) {
                audioSynth.start()
                soundBtn.text = "Stop Sound"
            } else {
                audioSynth.stop()
                soundBtn.text = "Start Sound"
            }
        }
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
            statusText.text = "Status: OBD2 device not found in paired list. Pair ELM327 first!"
            return
        }

        statusText.text = "Status: Connecting to ${obdDevice.name}..."

        Thread {
            try {
                bluetoothSocket = obdDevice.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket?.connect()
                isConnected = true

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
            outputStream.write("AT Z\r".toByteArray())
            Thread.sleep(300)
            outputStream.write("AT SP 0\r".toByteArray())
            Thread.sleep(200)
        } catch (e: Exception) {}

        val buffer = ByteArray(256)
        while (isConnected) {
            try {
                outputStream.write("010C\r".toByteArray())
                outputStream.flush()
                Thread.sleep(80)

                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    val rpm = parseRpmResponse(response)

                    if (rpm >= 0) {
                        val scaledR6Rpm = rpm * (16500.0 / 9500.0)

                        mainHandler.post {
                            bikeRpmText.text = "Bike RPM: ${rpm.toInt()}"
                            r6RpmText.text = "Yamaha R6 RPM: ${scaledR6Rpm.toInt()}"
                        }

                        if (isSoundActive) {
                            audioSynth.setRpm(scaledR6Rpm)
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
        audioSynth.setRpm(0.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectObd()
        audioSynth.stop()
    }
}

class EngineAudioSynth {
    private var isRunning = false
    private var audioTrack: AudioTrack? = null
    @Volatile private var targetFreq = 0.0
    private var currentFreq = 0.0

    fun start() {
        if (isRunning) return
        val sampleRate = 44100
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isRunning = true

        Thread {
            var phase = 0.0
            val buffer = ShortArray(1024)

            while (isRunning) {
                currentFreq += (targetFreq - currentFreq) * 0.15

                if (currentFreq < 15.0) {
                    buffer.fill(0)
                } else {
                    val phaseInc = (2.0 * Math.PI * currentFreq) / sampleRate
                    for (i in buffer.indices) {
                        val normPhase = phase / (2.0 * Math.PI)
                        val saw = 2.0 * (normPhase - Math.floor(normPhase + 0.5))
                        val sine2 = Math.sin(phase * 2.0)
                        val sine4 = Math.sin(phase * 4.0)
                        val noise = (Math.random() - 0.5) * 0.08

                        val sampleValue = (saw * 0.45 + sine2 * 0.30 + sine4 * 0.20 + noise) * 0.75
                        buffer[i] = (sampleValue * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                        phase += phaseInc
                        if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI
                    }
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }.start()
    }

    fun setRpm(r6Rpm: Double) {
        targetFreq = if (r6Rpm > 300) r6Rpm / 30.0 else 0.0
    }

    fun stop() {
        isRunning = false
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }
}
