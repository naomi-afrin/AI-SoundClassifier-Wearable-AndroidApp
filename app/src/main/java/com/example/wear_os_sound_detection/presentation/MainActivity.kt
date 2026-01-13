package com.example.wear_os_sound_detection.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

// for esp32
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

// for ui color
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color


class MainActivity : ComponentActivity() {

    private lateinit var tflite: Interpreter
    private val MODEL_PATH = "my_5layer_model2.tflite"

    // === ESP32 WebSocket URL & client (same names as phone app) ===
    private val esp32WebSocketUrl = "ws://192.168.4.1:81"
    private lateinit var espWebSocket: WebSocketClient
    private var isWebSocketConnected = false

    // === Prediction / direction state (mirror Android app) ===
    private var lastPrediction: String = ""
    private var lastConfidence: String = ""
    private var lastDirection: String = ""

    // UI-facing copies (mirror Android app)
    private var viewPrediction: String = "unknown"
    private var viewConfidence: String = "unknown"
    private var viewDirection: String = "unknown"

    private var isRecording by mutableStateOf(false)
    private var detectionResult by mutableStateOf("")
    private var isPlaying by mutableStateOf(false)
    private var recordButtonLabel by mutableStateOf("Record")

    private lateinit var audioRecord: AudioRecord
    private var bufferSize: Int = 0
    private var lastBuffer: ShortArray? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val outputLength = 5
    private val handler = Handler(Looper.getMainLooper())

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Button color state for flashing
    private var recordButtonColor = Color(0xFF4FC3F7)  // initial bright blue



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tflite = loadModel()
        setupESPWebSocket()   // connected to ESP32 here
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)



        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = detectionResult,
                        fontSize = 12.sp,
                        color = Color((0xFFE0E0E0)),
                        modifier = Modifier.padding(8.dp)
                    )

                    Button(
                        onClick = {
                            if (!isRecording) {
                                // Start recording
                                if (hasRecordAudioPermission()) {
                                    isRecording = true
                                    recordButtonLabel = "Stop Recording"
                                    detectionResult = "Recording..."
                                    startRecordingLoop()

                                } else {
                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                // Stop recording
                                isRecording = false
                                audioRecord.stop()
                                audioRecord.release()
                                recordButtonLabel = "Record"
                                detectionResult = "Recording stopped"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = recordButtonColor,
                            contentColor = Color((0xFF121212))
                        ),
                        modifier = Modifier.size(150.dp, 50.dp)
                    ) {
                        Text(recordButtonLabel, fontSize = 12.sp)
                    }

//                    Spacer(modifier = Modifier.height(16.dp))
//
//                    Button(
//                        onClick = {
//                            isPlaying = !isPlaying
//                        },
//                        modifier = Modifier.size(150.dp, 50.dp)
//                    ) {
//                        Text(if (isPlaying) "Stop Audio" else "Play Audio", fontSize = 12.sp)
//                    }
                }
            }
        }
    }

    private fun loadModel(): Interpreter {
        val assetFileDescriptor = assets.openFd(MODEL_PATH)
        val fileInputStream = assetFileDescriptor.createInputStream()
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        return Interpreter(modelBuffer)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                isRecording = true
                recordButtonLabel = "Stop Recording"
                detectionResult = "Recording..."
                startRecordingLoop()
            } else {
                detectionResult = "Permission denied."
            }
        }



    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeAudioRecord() {
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
    }

    private fun startRecordingLoop() {
        if (!hasRecordAudioPermission()) {
            detectionResult = "Permission not granted!"
            return
        }

        initializeAudioRecord()
        audioRecord.startRecording()

        CoroutineScope(Dispatchers.IO).launch {
            while (isRecording) {
                val buffer = ShortArray(sampleRate * 3)
                var totalRead = 0
                while (totalRead < buffer.size) {
                    val read = audioRecord.read(buffer, totalRead, buffer.size - totalRead)
                    if (read > 0) totalRead += read
                }
                val startTime = System.currentTimeMillis()
                lastBuffer = buffer

                // ✅ Ask ESP32 for direction
                // -----------------------------
                requestDirectionFromESP32()

                val predictedLabel = runModel(buffer)
                lastPrediction= predictedLabel
                // update ui vals
                updateUIWithResults()

                withContext(Dispatchers.Main) {
                    detectionResult = "Detected: $lastPrediction \n Dir: $viewDirection\n conf: $viewConfidence% "
                    if (isPlaying) {
                        playAudio(buffer)
                    }
                }

//                delay(3000)
//
//                withContext(Dispatchers.Main) {
//                    if (isRecording) detectionResult = "Recording..."
//                }
                val endTime = System.currentTimeMillis()
                Log.d("Time", "Time between recording: ${endTime - startTime} ms")
            }

            // Ensure recording stops cleanly
            if (this@MainActivity::audioRecord.isInitialized) {
                audioRecord.stop()
                audioRecord.release()

            }


        }
    }

    // -------------------------
// ESP32 WEBSOCKET
// -------------------------
    private fun setupESPWebSocket() {
        val wsUri = URI("ws://192.168.4.1:81/")  // same as Android app
        espWebSocket = object : WebSocketClient(wsUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isWebSocketConnected = true
                Log.d("ESP32-WS", "Connected to ESP32")
            }

            override fun onMessage(message: String?) {
                message?.let {
                    lastDirection = it
                    Log.d("ESP32-WS", "Received direction: $it")
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isWebSocketConnected = false
                Log.d("ESP32-WS", "WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                isWebSocketConnected = false
                Log.e("ESP32-WS", "WebSocket error: ${ex?.message}")
            }
        }
        espWebSocket.connect()
    }

    private fun requestDirectionFromESP32() {
        if (isWebSocketConnected) {
            espWebSocket.send("GET_DIRECTION")
            Log.d("ESP32-WS", "Sent GET_DIRECTION")
        } else {
            Log.d("ESP32-WS", "WebSocket not connected yet")
            viewDirection = "socketFalse"
        }
    }


    private fun runModel(buffer: ShortArray): String {
        findMaxAndMinValues(buffer)
        val floatInput = buffer.map { it.toFloat() / 32768.0f }.toFloatArray()
        val input2D = arrayOf(floatInput)
        val output = Array(1) { FloatArray(outputLength) }
        tflite.run(input2D, output)
        val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
        lastConfidence = output[0].getOrNull(predictedIndex)?.let { "%.2f".format(it * 100) } ?: "N/A"
        return when (predictedIndex) {
            0 -> "Other"
            1 -> "Car Horn 🚗"
            2 -> "Scream 🔊"
            3 -> "Dog Bark 🐕"
            4 -> "Calling Bell 🔔"
            else -> "Unknown"
        }

    }

    private fun playAudio(buffer: ShortArray) {
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            buffer.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        val audioBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.forEach { audioBuffer.putShort(it) }

        audioTrack.write(audioBuffer.array(), 0, audioBuffer.array().size)
        audioTrack.play()

        handler.postDelayed({
            audioTrack.stop()
            audioTrack.release()
        }, 3000)
    }

    fun findMaxAndMinValues(buffer: ShortArray) {
        var maxValue = Short.MIN_VALUE
        var minValue = Short.MAX_VALUE

        for (i in buffer.indices) {
            if (buffer[i] > maxValue) maxValue = buffer[i]
            if (buffer[i] < minValue) minValue = buffer[i]
        }

        Log.d("AudioBuffer", "Maximum value in the buffer: $maxValue")
        Log.d("AudioBuffer", "Minimum value in the buffer: $minValue")
    }

    // Function to Vibrate and pause recording during vibration
    private fun vibrateAndPauseRecording() {
        // --- Pause/Stop recording before vibrating ---
        audioRecord.stop()   // use your existing stopRecording() function
//        audioRecord.release()


        // --- Get Vibrator service ---
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        // --- Trigger vibration ---
        val effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)

        // --- Resume recording after vibration duration ---
        Handler(Looper.getMainLooper()).postDelayed({
            audioRecord.startRecording()   // use your existing startRecording() function
        }, 500) // delay matches vibration duration (500 ms)
    }


    // Function to update UI
    private fun updateUIWithResults() {
        viewPrediction = lastPrediction
        viewConfidence = lastConfidence
        viewDirection = lastDirection

        if (viewDirection == "Right"){
            viewDirection = "Right ➡️"
        }
        if (viewDirection == "Left"){
            viewDirection = "Left ⬅️"
        }
        if (viewDirection == "Front"){
            viewDirection = "Front ⬆️"
        }
        if (viewDirection == "Back"){
            viewDirection = "Back ⬇️"}

        if (viewDirection == "No Sound"){
            viewDirection = "No Sound"}



        // To activate vibration
        if (viewPrediction != "Other") {
            Log.d("vibrate", "Going to vibrateOneSecond() function because prediction is$lastPrediction")
            vibrateAndPauseRecording()
        }
    }


}
