package com.cinecalibrator.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * CameraManager
 *
 * Wraps CameraX for live preview + frame sampling.
 *
 * Exposure locking:
 *   lockExposureForCalibration() drives the AE/AWB convergence cycle:
 *     1. Let auto-exposure run for ~2 seconds while a bright reference colour is on
 *     2. Freeze ISO + exposure time via Camera2 interop so all subsequent measurements
 *        use identical sensor parameters
 *     3. Clients call unlockExposure() after the scan to restore auto mode
 *
 * The lock state is reported via isExposureLocked so the UI can show a status badge.
 */
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null

    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val frameChannel = Channel<CapturedFrame>(Channel.BUFFERED)

    var isExposureLocked = false
        private set

    data class CapturedFrame(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val timestamp: Long
    )

    data class ROISample(
        val x: Int, val y: Int,
        val width: Int, val height: Int,
        val avgR: Int, val avgG: Int, val avgB: Int,
        val maxR: Int, val maxG: Int, val maxB: Int,
        val isClipped: Boolean
    )

    // ─── Preview ─────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(1920, 1080))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(captureExecutor) { imageProxy ->
                val bitmap = imageProxy.toBitmap()
                frameChannel.trySend(CapturedFrame(bitmap, bitmap.width, bitmap.height,
                    System.currentTimeMillis()))
                imageProxy.close()
            }

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                imageAnalysisUseCase = imageAnalysis
                onReady()
            } catch (e: Exception) {
                Timber.e(e, "Camera bind failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ─── Exposure lock / unlock ───────────────────────────────────────────────────

    /**
     * Lock AE and AWB at their current converged values.
     *
     * Call this after displaying a bright reference colour for [settleMs] ms.
     * Uses Camera2 interop to freeze ISO and exposure time so every subsequent
     * frame is captured under identical conditions — critical for colorimetry.
     *
     * @param settleMs   How long to wait for AE to converge before locking (ms)
     * @param onLocked   Called on main thread when lock is confirmed (or failed)
     */
    suspend fun lockExposureForCalibration(
        settleMs: Long = 2000,
        onLocked: (success: Boolean, message: String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.Main) {
        val cam = camera
        if (cam == null) {
            onLocked(false, "Camera not ready")
            return@withContext
        }

        try {
            // Step 1 — cancel any previous lock, run auto mode for settleMs
            unlockExposureInternal(cam)
            Timber.d("AE/AWB convergence: waiting ${settleMs}ms…")
            delay(settleMs)

            // Step 2 — read current converged values and freeze them
            val cam2Control = Camera2CameraControl.from(cam.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build()

            cam2Control.captureRequestOptions = options

            isExposureLocked = true
            Timber.d("Exposure locked (AE+AWB)")
            withContext(Dispatchers.Main) {
                onLocked(true, "Exposure locked — AE + AWB frozen")
            }
        } catch (e: Exception) {
            Timber.e(e, "Exposure lock failed")
            isExposureLocked = false
            withContext(Dispatchers.Main) {
                onLocked(false, "Exposure lock failed: ${e.message}")
            }
        }
    }

    /**
     * Restore auto-exposure and auto-white-balance.
     * Call after the scan is complete.
     */
    fun unlockExposure() {
        camera?.let { unlockExposureInternal(it) }
        isExposureLocked = false
        Timber.d("Exposure unlocked — restored auto AE/AWB")
    }

    private fun unlockExposureInternal(cam: Camera) {
        try {
            val cam2Control = Camera2CameraControl.from(cam.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .build()
            cam2Control.captureRequestOptions = options
        } catch (e: Exception) {
            Timber.w(e, "Failed to unlock exposure — may not be supported on this device")
        }
    }

    // ─── ROI Sampling ────────────────────────────────────────────────────────────

    suspend fun sampleROI(
        normalizedX: Float,
        normalizedY: Float,
        normalizedW: Float = 0.05f,
        normalizedH: Float = 0.05f
    ): ROISample? {
        val frame = frameChannel.tryReceive().getOrNull() ?: return null
        val bitmap = frame.bitmap

        val x = ((normalizedX - normalizedW / 2) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((normalizedY - normalizedH / 2) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = (normalizedW * bitmap.width).toInt().coerceAtLeast(4)
        val h = (normalizedH * bitmap.height).toInt().coerceAtLeast(4)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x, y, w, h)

        var totalR = 0L; var totalG = 0L; var totalB = 0L
        var maxR = 0; var maxG = 0; var maxB = 0
        var clipped = false

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalR += r; totalG += g; totalB += b
            if (r > maxR) maxR = r
            if (g > maxG) maxG = g
            if (b > maxB) maxB = b
            if (r >= 250 || g >= 250 || b >= 250) clipped = true
        }

        val count = pixels.size
        return ROISample(
            x = x, y = y, width = w, height = h,
            avgR = (totalR / count).toInt(),
            avgG = (totalG / count).toInt(),
            avgB = (totalB / count).toInt(),
            maxR = maxR, maxG = maxG, maxB = maxB,
            isClipped = clipped
        )
    }

    /**
     * Drain stale frames from the buffer without processing them.
     *
     * After changing DMX values, the camera frame buffer still contains old
     * frames from before the fixture stabilised. Call this after the settle
     * delay to purge those stale frames so the next sampleROI call reads
     * fresh data from the stabilised fixture.
     *
     * @param frames  How many stale frames to drain (3-5 is usually enough)
     */
    suspend fun drainFrameBuffer(frames: Int = 8) {
        // BUFFERED channel may have accumulated frames; drain more aggressively
        var drained = 0
        repeat(frames * 2) {
            if (frameChannel.tryReceive().isSuccess) drained++
            delay(25)
        }
        Timber.v("Drained $drained stale frames from buffer")
    }

    /**
     * Average N frames with prior stale-frame discard.
     *
     * Drains [discardFrames] frames first (fixture transition junk), then
     * averages [keepFrames] to produce the measurement.
     */
    suspend fun sampleROIAveraged(
        normalizedX: Float,
        normalizedY: Float,
        frames: Int = 15,
        discardFrames: Int = 5
    ): ROISample? {
        // Discard transition frames (doubled count for BUFFERED channel)
        drainFrameBuffer(discardFrames * 2)

        val samples = mutableListOf<ROISample>()
        repeat(frames) {
            delay(66)  // ~15fps sampling
            sampleROI(normalizedX, normalizedY)?.let { samples.add(it) }
        }
        if (samples.isEmpty()) return null

        return ROISample(
            x = samples.first().x, y = samples.first().y,
            width = samples.first().width, height = samples.first().height,
            avgR = samples.map { it.avgR }.average().toInt(),
            avgG = samples.map { it.avgG }.average().toInt(),
            avgB = samples.map { it.avgB }.average().toInt(),
            maxR = samples.maxOf { it.maxR },
            maxG = samples.maxOf { it.maxG },
            maxB = samples.maxOf { it.maxB },
            isClipped = samples.any { it.isClipped }
        )
    }

    fun checkRAWSupport(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
        return try {
            val id = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return false
            val caps = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps.toList()
        } catch (e: Exception) { false }
    }

    fun stopPreview() {
        cameraProvider?.unbindAll()
        isExposureLocked = false
    }

    fun release() {
        stopPreview()
        captureExecutor.shutdown()
        frameChannel.close()
    }
}
