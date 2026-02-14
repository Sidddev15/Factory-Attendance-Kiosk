package com.siddharth.factoryattendance.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraStatus: TextView

    private lateinit var dbHelper: AttendanceDbHelper
    private lateinit var cameraExecutor: ExecutorService

    private var punchId: Long = -1L
    private var imageCapture: ImageCapture? = null

    companion object {
        private const val REQ_CAMERA = 1001
        const val EXTRA_PUNCH_ID = "EXTRA_PUNCH_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚨 CRITICAL: mark camera active BEFORE anything else
        AppState.isCameraActive = true

        setContentView(R.layout.activity_camera_capture)

        previewView = findViewById(R.id.previewView)
        cameraStatus = findViewById(R.id.cameraStatus)

        dbHelper = AttendanceDbHelper(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        punchId = intent.getLongExtra(EXTRA_PUNCH_ID, -1L)
        if (punchId <= 0) {
            Log.e("CAM", "Missing punchId, finishing")
            finishSafely()
            return
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQ_CAMERA
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_CAMERA &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Log.e("CAM", "Camera permission denied")
            finishSafely()
        }
    }

    private fun startCamera() {
        cameraStatus.text = "Capturing..."

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val front = CameraSelector.DEFAULT_FRONT_CAMERA
            val back = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                val selector =
                    if (cameraProvider.hasCamera(front)) front else back

                cameraProvider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture
                )

                // Give exposure time, then auto-capture
                previewView.postDelayed({ takePhoto() }, 600)

            } catch (e: Exception) {
                Log.e("CAM", "Camera bind failed", e)
                finishSafely()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            Log.e("CAM", "ImageCapture null")
            finishSafely()
            return
        }

        val ts = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

        val dir = File(getExternalFilesDir(null), "attendance_photos/$day")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "punch_${punchId}_$ts.jpg")

        val output = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            output,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    Log.d("CAM", "Saved photo: ${file.absolutePath}")
                    savePhotoPathToDb(file.absolutePath)

                    runOnUiThread {
                        cameraStatus.text = "✅ Saved"
                        previewView.postDelayed({ finishSafely() }, 400)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CAM", "Capture failed", exception)

                    runOnUiThread {
                        cameraStatus.text = "❌ Capture failed"
                        previewView.postDelayed({ finishSafely() }, 600)
                    }
                }
            }
        )
    }

    private fun savePhotoPathToDb(path: String) {
        val db = dbHelper.writableDatabase
        db.execSQL(
            "UPDATE punches SET photo_path = ? WHERE id = ?",
            arrayOf(path, punchId)
        )
    }

    // ✅ SINGLE exit point — avoids kiosk conflicts
    private fun finishSafely() {
        AppState.isCameraActive = false

        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {}

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppState.isCameraActive = false

        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
