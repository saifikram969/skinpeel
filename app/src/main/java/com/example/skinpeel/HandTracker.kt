package com.example.skinpeel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HandResult(
    val hands: List<List<NormalizedLandmark>>,
    val imageWidth: Int,
    val imageHeight: Int
)

class HandTracker(
    context: Context,
    private val onResult: (HandResult) -> Unit
) {
    private val landmarker: HandLandmarker

    @Volatile private var imgW = 1
    @Volatile private var imgH = 1
    private var lastTs = 0L

    init {
        val bytes = context.assets.open("hand_landmarker.task").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
            .also { it.rewind() }

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetBuffer(modelBuffer)
                    .setDelegate(Delegate.CPU)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ ->
                onResult(HandResult(result.landmarks(), imgW, imgH))
            }
            .setErrorListener { Log.e("HandTracker", "error", it) }
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = imageProxy.toBitmap()
        imageProxy.close()

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
        }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        imgW = rotated.width
        imgH = rotated.height

        val ts = maxOf(SystemClock.uptimeMillis(), lastTs + 1)
        lastTs = ts
        landmarker.detectAsync(BitmapImageBuilder(rotated).build(), ts)
    }

    fun close() = landmarker.close()
}