//package com.example.wear_os_sound_detection.presentation
//
//import android.Manifest
//import android.content.pm.PackageManager
//import android.media.MediaPlayer
//import android.media.MediaRecorder
//import android.os.Bundle
//import android.util.Log
//import androidx.activity.ComponentActivity
//import androidx.core.app.ActivityCompat
//import androidx.core.content.ContextCompat
//import kotlinx.coroutines.*
//import java.io.File
//import java.io.FileInputStream
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//
//class MainActivity : ComponentActivity() {
//
//    private lateinit var fileName: String
//    private var recorder: MediaRecorder? = null
//    private var player: MediaPlayer? = null
//    private val REQUEST_RECORD_AUDIO_PERMISSION = 200
//    private val LOG_TAG = "WearAudioTest"
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        fileName = "${externalCacheDir?.absolutePath}/audiorecordtest.3gp"
//
//        // Request permission
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
//            != PackageManager.PERMISSION_GRANTED
//        ) {
//            ActivityCompat.requestPermissions(
//                this,
//                arrayOf(Manifest.permission.RECORD_AUDIO),
//                REQUEST_RECORD_AUDIO_PERMISSION
//            )
//        } else {
//            recordThreeSeconds()
//        }
//    }
//
//    private fun recordThreeSeconds() {
//        CoroutineScope(Dispatchers.Main).launch {
//            recorder = MediaRecorder().apply {
//                setAudioSource(MediaRecorder.AudioSource.MIC)
//                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
//                setOutputFile(fileName)
//                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
//                prepare()
//                start()
//            }
//
//            Log.d(LOG_TAG, "Recording started")
//            delay(3000) // record for 3 seconds
//            recorder?.apply {
//                stop()
//                release()
//            }
//            recorder = null
//            Log.d(LOG_TAG, "Recording finished")
//
//            // Read PCM buffer from the 3GP file
//            val bufferStats = read3GPPFileToPCM(File(fileName))
//            Log.d(LOG_TAG, "Buffer stats -> min: ${bufferStats.first}, max: ${bufferStats.second}, avg abs: ${bufferStats.third}")
//
//            // Playback
//            player = MediaPlayer().apply {
//                setDataSource(fileName)
//                prepare()
//                start()
//            }
//            Log.d(LOG_TAG, "Playback started")
//            player?.setOnCompletionListener {
//                Log.d(LOG_TAG, "Playback finished")
//                it.release()
//                player = null
//            }
//        }
//    }
//
//    private fun read3GPPFileToPCM(file: File): Triple<Int, Int, Double> {
//        // Convert 3GP/AMR to PCM is non-trivial, for demo we'll approximate by reading raw bytes
//        val fis = FileInputStream(file)
//        val bytes = fis.readBytes()
//        fis.close()
//
//        // Convert bytes to shorts (16-bit PCM approximation)
//        val shortBuffer = ShortArray(bytes.size / 2)
//        val byteBuffer = ByteBuffer.wrap(bytes)
//        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
//        var i = 0
//        while (byteBuffer.remaining() >= 2 && i < shortBuffer.size) {
//            shortBuffer[i++] = byteBuffer.short
//        }
//
//        var min = Short.MAX_VALUE.toInt()
//        var max = Short.MIN_VALUE.toInt()
//        var sumAbs = 0L
//        for (s in shortBuffer) {
//            if (s < min) min = s.toInt()
//            if (s > max) max = s.toInt()
//            sumAbs += kotlin.math.abs(s.toInt())
//        }
//        val avgAbs = if (shortBuffer.isNotEmpty()) sumAbs.toDouble() / shortBuffer.size else 0.0
//
//        return Triple(min, max, avgAbs)
//    }
//}
