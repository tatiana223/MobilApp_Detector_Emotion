package com.example.emotiondetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.face.FaceDetectorOptions
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.emotiondetector.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import android.os.Handler
import android.os.Looper
import android.os.Build

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    companion object {
        private const val TAG = "EmotionDetector"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        checkDeviceCapabilities()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "View initialized")

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Log.w(TAG, "Camera not supported")
            showToastAndFinish("Устройство не поддерживает камеру")
            return
        }

        try {
            cameraExecutor = Executors.newSingleThreadExecutor()
            initFaceDetector()
            Log.d(TAG, "Components initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            showToastAndFinish("Ошибка инициализации")
            return
        }

        if (allPermissionsGranted()) {
            Log.d(TAG, "Permissions granted, starting camera")
            startCamera()
        } else {
            Log.d(TAG, "Requesting permissions")
            requestPermissions()
        }
    }

    private fun initFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = FaceDetection.getClient(options)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            REQUEST_CODE_PERMISSIONS
        )
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ::processImage)
                    }

                val cameraSelector = if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска камеры", e)
                showToastAndFinish("Ошибка запуска камеры")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        processFaces(faces)
                    } else {
                        updateUI("Лицо не обнаружено", Color.GRAY)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Ошибка детекции лиц", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки изображения", e)
            imageProxy.close()
        }
    }

    private fun processFaces(faces: List<Face>) {
        val face = faces.first()
        val smileProb = face.smilingProbability ?: 0f
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 0f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 0f

        val isSmiling = smileProb > 0.5f
        val eyesClosed = leftEyeOpenProb < 0.3f && rightEyeOpenProb < 0.3f
        val weakSmile = smileProb in 0.2f..0.4f
        val neutralFace = smileProb < 0.2f

        val (emotionText, emotionColor) = when {
            eyesClosed && neutralFace -> "Депрессия" to Color.BLUE
            !isSmiling && eyesClosed -> "Грусть" to Color.CYAN
            weakSmile && eyesClosed -> "Усталость" to Color.MAGENTA
            isSmiling -> "Радость" to Color.GREEN
            else -> "Нейтральное" to Color.GRAY
        }

        updateUI(emotionText, emotionColor)
    }

    private fun updateUI(text: String, color: Int) {
        runOnUiThread {
            binding.emotionTextView.text = text
            binding.emotionTextView.setTextColor(color)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showToastAndFinish("Необходимы разрешения для работы приложения")
            }
        }
    }

    private fun showToastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    fun checkDeviceCapabilities() {
        val pm = packageManager

        val cameraCheck = if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            "Камера поддерживается"
        } else {
            "Камера НЕ поддерживается"
        }

        val frontCameraCheck = if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            "Фронтальная камера есть"
        } else {
            "Нет фронтальной камеры"
        }

        Log.d(TAG, """
        ====== ХАРАКТЕРИСТИКИ УСТРОЙСТВА ======
        $cameraCheck
        $frontCameraCheck
        Версия Android: ${Build.VERSION.RELEASE}
        Производитель: ${Build.MANUFACTURER}
        Модель: ${Build.MODEL}
    """.trimIndent())
    }
}