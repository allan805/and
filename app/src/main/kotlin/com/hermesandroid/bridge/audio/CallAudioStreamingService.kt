package com.hermesandroid.bridge.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import com.hermesandroid.bridge.client.RelayClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Realtime telephone downlink audio streamer.
 *
 * Pipeline:
 *
 *   cellular call
 *        |
 *        v
 *   VOICE_DOWNLINK
 *        |
 *        v
 *   AudioRecord
 *        |
 *        v
 *   PCM16 / 16kHz / mono
 *        |
 *        v
 *   40ms frames
 *        |
 *        v
 *   RelayClient WebSocket binary
 *
 * This service is intentionally separate from MicrophoneRecorderService.
 */
class CallAudioStreamingService : Service() {

    companion object {
        private const val TAG = "CallAudioStream"

        private const val ACTION_START = "call_audio_start"
        private const val ACTION_STOP = "call_audio_stop"

        private const val EXTRA_REQUEST_ID = "request_id"

        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * 40ms at 16kHz mono PCM16:
         *
         * 16000 * 0.04 = 640 samples
         * 640 * 2 = 1280 bytes
         */
        private const val FRAME_SAMPLES = 640
        private const val FRAME_BYTES = FRAME_SAMPLES * 2

        private const val CHANNEL_ID = "hermes_call_audio"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        private var running = false

        @Volatile
        private var currentRequestId: String? = null

        @Volatile
        private var totalBytes: Long = 0

        @Volatile
        private var totalFrames: Long = 0

        fun start(context: Context): String {
            val requestId = UUID.randomUUID().toString()

            val intent = Intent(
                context,
                CallAudioStreamingService::class.java,
            ).apply {
                action = ACTION_START
                putExtra(EXTRA_REQUEST_ID, requestId)
            }

            context.startForegroundService(intent)

            return requestId
        }

        fun stop(context: Context) {
            val intent = Intent(
                context,
                CallAudioStreamingService::class.java,
            ).apply {
                action = ACTION_STOP
            }

            context.startForegroundService(intent)
        }

        fun isRunning(): Boolean = running

        fun requestId(): String? = currentRequestId

        fun bytes(): Long = totalBytes

        fun frames(): Long = totalFrames

        fun status(): Map<String, Any?> {
            return mapOf(
                "running" to running,
                "requestId" to currentRequestId,
                "bytes" to totalBytes,
                "frames" to totalFrames,
                "sampleRate" to SAMPLE_RATE,
                "channels" to 1,
                "encoding" to "PCM16",
                "frameMs" to 40,
                "source" to "VOICE_DOWNLINK",
            )
        }
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stopRequested =
        AtomicBoolean(false)

    @Volatile
    private var recorder: AudioRecord? = null

    private var streamingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Preparing call audio…"),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                requestStop()
            }

            ACTION_START,
            null -> {
                val requestId =
                    intent?.getStringExtra(EXTRA_REQUEST_ID)
                        ?: UUID.randomUUID().toString()

                startStreaming(requestId)
            }
        }

        return START_NOT_STICKY
    }

    private fun startStreaming(requestId: String) {

        if (running) {
            Log.w(
                TAG,
                "Realtime call audio is already running: $currentRequestId",
            )
            return
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(
                TAG,
                "RECORD_AUDIO permission is not granted",
            )
            stopForegroundCompat()
            stopSelf()
            return
        }

        stopRequested.set(false)

        running = true
        currentRequestId = requestId
        totalBytes = 0
        totalFrames = 0

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_MASK,
            ENCODING,
        )

        if (minimumBuffer <= 0) {
            Log.e(
                TAG,
                "AudioRecord.getMinBufferSize failed: $minimumBuffer",
            )
            failAndStop()
            return
        }

        /*
         * We use a buffer larger than one 40ms frame so AudioRecord has
         * some room during scheduling/network jitter.
         */
        val bufferSize = maxOf(
            minimumBuffer * 4,
            FRAME_BYTES * 4,
        )

        val audioRecord = runCatching {

            AudioRecord(
                /*
                 * Important:
                 *
                 * This is deliberately NOT VOICE_RECOGNITION.
                 *
                 * We are testing the telephone downlink.
                 */
                MediaRecorder.AudioSource.VOICE_CALL,

                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING,
                bufferSize,
            )

        }.getOrElse { error ->

            Log.e(
                TAG,
                "Could not initialize VOICE_DOWNLINK AudioRecord",
                error,
            )

            failAndStop()
            return
        }

        if (
            audioRecord.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            Log.e(
                TAG,
                "VOICE_DOWNLINK AudioRecord is not initialized",
            )

            runCatching {
                audioRecord.release()
            }

            failAndStop()
            return
        }

        recorder = audioRecord

        streamingJob = scope.launch {

            try {

                audioRecord.startRecording()

                if (
                    audioRecord.recordingState !=
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    throw IllegalStateException(
                        "VOICE_DOWNLINK did not enter recording state",
                    )
                }

                Log.i(
                    TAG,
                    "Realtime call audio started: requestId=$requestId",
                )

                updateNotification(
                    "Streaming telephone downlink audio…",
                )

                val samples =
                    ShortArray(FRAME_SAMPLES)

                while (
                    isActive &&
                    running &&
                    !stopRequested.get()
                ) {

                    val read = audioRecord.read(
                        samples,
                        0,
                        FRAME_SAMPLES,
                        AudioRecord.READ_BLOCKING,
                    )

                    if (read < 0) {
                        throw IllegalStateException(
                            "AudioRecord.read failed: $read",
                        )
                    }

                    if (read == 0) {
                        continue
                    }

                    val payload =
                        shortsToPcm16(
                            samples,
                            read,
                        )

                    /*
                     * RelayClient sends:
                     *
                     *   2 bytes request ID length
                     *   N bytes request ID
                     *   PCM payload
                     */
                    val sent =
                        RelayClient.sendRealtimeAudio(
                            requestId,
                            payload,
                        )

                    if (!sent) {
                        throw IllegalStateException(
                            "RelayClient WebSocket is not connected",
                        )
                    }

                    totalBytes += payload.size
                    totalFrames++

                    if (totalFrames % 25L == 0L) {
                        Log.i(
                            TAG,
                            "AUDIO_TX request=$requestId " +
                                "frames=$totalFrames " +
                                "bytes=$totalBytes",
                        )
                    }
                }

            } catch (error: Throwable) {

                Log.e(
                    TAG,
                    "Realtime call audio failed",
                    error,
                )

            } finally {

                safelyStop(audioRecord)

                runCatching {
                    audioRecord.release()
                }

                if (recorder === audioRecord) {
                    recorder = null
                }

                running = false
                stopRequested.set(false)

                Log.i(
                    TAG,
                    "Realtime call audio stopped: " +
                        "frames=$totalFrames " +
                        "bytes=$totalBytes",
                )

                updateNotification("Call audio stopped")

                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun shortsToPcm16(
        samples: ShortArray,
        count: Int,
    ): ByteArray {

        val output = ByteArray(count * 2)

        val buffer = ByteBuffer
            .wrap(output)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until count) {
            buffer.putShort(samples[i])
        }

        return output
    }

    private fun requestStop() {

        if (!running) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        Log.i(TAG, "Stop requested")

        stopRequested.set(true)
        running = false

        safelyStop(recorder)
    }

    private fun failAndStop() {

        running = false
        stopRequested.set(false)

        stopForegroundCompat()
        stopSelf()
    }

    private fun safelyStop(
        audioRecord: AudioRecord?,
    ) {
        if (audioRecord == null) return

        runCatching {
            if (
                audioRecord.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                audioRecord.stop()
            }
        }
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hermes call audio",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description =
                "Visible indicator while Hermes captures telephone downlink audio"
        }

        getSystemService(
            NotificationManager::class.java,
        ).createNotificationChannel(channel)
    }

    private fun buildNotification(
        text: String,
    ): Notification {

        return Notification.Builder(
            this,
            CHANNEL_ID,
        )
            .setContentTitle("Hermes call audio")
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now,
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        text: String,
    ) {

        runCatching {
            getSystemService(
                NotificationManager::class.java,
            ).notify(
                NOTIFICATION_ID,
                buildNotification(text),
            )
        }
    }

    private fun stopForegroundCompat() {

        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {

        stopRequested.set(true)

        safelyStop(recorder)

        streamingJob?.cancel()

        scope.cancel()

        running = false

        super.onDestroy()
    }
}
