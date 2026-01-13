//package com.example.wear_os_sound_detection.presentation
//
//import android.Manifest
//import android.content.pm.PackageManager
//import android.media.*
//import android.os.*
//import android.util.Log
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.core.content.ContextCompat
//import kotlinx.coroutines.*
//import org.tensorflow.lite.Interpreter
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.nio.channels.FileChannel
//import java.util.concurrent.Executors
//import java.util.concurrent.ScheduledExecutorService
//
//class MainActivity : ComponentActivity() {
//
//    private lateinit var tflite: Interpreter
//    private val MODEL_PATH = "classifier_with_melspectrogram.tflite"
//
//    private var isRecording by mutableStateOf(false)
//    private var detectionResult by mutableStateOf("")
//    private var showRecordButton by mutableStateOf(true)
//    private var playButtonLabel by mutableStateOf("Play")
//    private var isPlaying by mutableStateOf(false)
//
//    private lateinit var audioRecord: AudioRecord
//    private var bufferSize: Int = 0
//    private var lastBuffer: ShortArray? = null
//
//    private val sampleRate = 16000
//    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
//    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
//    private val outputLength = 4
//    private val handler = Handler(Looper.getMainLooper())
//
//    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        tflite = loadModel()
//
//        setContent {
//            MaterialTheme {
//                Column(
//                    modifier = Modifier.fillMaxSize(),
//                    horizontalAlignment = Alignment.CenterHorizontally,
//                    verticalArrangement = Arrangement.Center
//                ) {
//                    if (showRecordButton) {
//                        Button(
//                            onClick = {
//                                if (hasRecordAudioPermission()) {
//                                    showRecordButton = false
//                                    detectionResult = "Recording..."
//                                    startRecordingLoop()
//                                } else {
//                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//                                }
//                            },
//                            modifier = Modifier.size(100.dp, 40.dp)
//                        ) {
//                            Text("Record", fontSize = 12.sp)
//                        }
//                    } else {
//                        Text(
//                            text = detectionResult,
//                            fontSize = 12.sp,
//                            modifier = Modifier.padding(8.dp)
//                        )
//                        Button(
//                            onClick = {
//                                if (isPlaying) {
//                                    isPlaying = false
//                                    playButtonLabel = "Play"
//                                } else {
//                                    isPlaying = true
//                                    playButtonLabel = "Stop"
//                                }
//                            },
//                            modifier = Modifier.size(100.dp, 40.dp)
//                        ) {
//                            Text(playButtonLabel, fontSize = 12.sp)
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    private fun loadModel(): Interpreter {
//        val assetFileDescriptor = assets.openFd(MODEL_PATH)
//        val fileInputStream = assetFileDescriptor.createInputStream()
//        val fileChannel = fileInputStream.channel
//        val startOffset = assetFileDescriptor.startOffset
//        val declaredLength = assetFileDescriptor.declaredLength
//        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
//        return Interpreter(modelBuffer)
//    }
//
//    private val requestPermissionLauncher =
//        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
//            if (isGranted) {
//                showRecordButton = false
//                detectionResult = "Recording..."
//                startRecordingLoop()
//            } else {
//                detectionResult = "Permission denied."
//            }
//        }
//
//    private fun hasRecordAudioPermission(): Boolean {
//        return ContextCompat.checkSelfPermission(
//            this, Manifest.permission.RECORD_AUDIO
//        ) == PackageManager.PERMISSION_GRANTED
//    }
//
//    private fun initializeAudioRecord() {
//        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
//        audioRecord = AudioRecord(
//            MediaRecorder.AudioSource.MIC,
//            sampleRate,
//            channelConfig,
//            audioFormat,
//            bufferSize
//        )
//    }
//
//    private fun startRecordingLoop() {
//        initializeAudioRecord()
//        isRecording = true
//
//        CoroutineScope(Dispatchers.IO).launch {
//            while (isRecording) {
//                audioRecord.startRecording()
//                val buffer = ShortArray(sampleRate * 3)
//                var totalRead = 0
//                while (totalRead < buffer.size) {
//                    val read = audioRecord.read(buffer, totalRead, buffer.size - totalRead)
//                    if (read > 0) totalRead += read
//                }
//                audioRecord.stop()
//
//                lastBuffer = buffer
//
//                // Process prediction
//                val predictedLabel = runModel(buffer)
//
//                withContext(Dispatchers.Main) {
//                    detectionResult = "Detected: $predictedLabel"
//
//                    // Vibrate for 0.5s
//                    val vibratorManager = getSystemService(VibratorManager::class.java)
//                    vibratorManager?.defaultVibrator?.vibrate(
//                        VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
//                    )
//
//                    // Play audio if enabled
//                    if (isPlaying) {
//                        playAudio(buffer)
//                    }
//                }
//
//                // Show result for 3 seconds
//                delay(3000)
//
//                withContext(Dispatchers.Main) {
//                    detectionResult = "Recording..."
//                }
//            }
//        }
//    }
//
//    private fun runModel(buffer: ShortArray): String {
//        val floatInput = buffer.map { it.toFloat() / 32768.0f }.toFloatArray()
//        val input2D = arrayOf(floatInput)
//        val output = Array(1) { FloatArray(outputLength) }
//        tflite.run(input2D, output)
//        val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
//        return when (predictedIndex) {
//            0 -> "Other"
//            1 -> "Car Horn"
//            2 -> "Scream"
//            3 -> "Dog Bark"
//            else -> "Unknown"
//        }
//    }
//
//    private fun playAudio(buffer: ShortArray) {
//        val audioTrack = AudioTrack(
//            AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_MEDIA)
//                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                .build(),
//            AudioFormat.Builder()
//                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
//                .setSampleRate(sampleRate)
//                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
//                .build(),
//            buffer.size * 2,
//            AudioTrack.MODE_STATIC,
//            AudioManager.AUDIO_SESSION_ID_GENERATE
//        )
//
//        val audioBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
//        buffer.forEach { audioBuffer.putShort(it) }
//
//        audioTrack.write(audioBuffer.array(), 0, audioBuffer.array().size)
//        audioTrack.play()
//
//        handler.postDelayed({
//            audioTrack.stop()
//            audioTrack.release()
//        }, 3000)
//    }
//}
