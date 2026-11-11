package com.zfdang.chess.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.zfdang.chess.R
import java.io.File

/**
 * 拍照识别摆谱：CameraX 高清拍照（2560×1920 + 3:4 引导框），
 * JPEG 路径经 EXTRA_PATH 交给 ScanActivity 识别。
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var preview: androidx.camera.view.PreviewView
    private lateinit var btnShot: Button
    private var imageCapture: ImageCapture? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "需要相机权限才能拍照识别", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        preview = findViewById(R.id.preview)
        btnShot = findViewById(R.id.btnShot)
        findViewById<TextView>(R.id.capHint).text = getString(R.string.cap_hint)
        btnShot.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(2560, 1920),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)
                            )
                            .build()
                    )
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        btnShot.isEnabled = false
        val dir = File(cacheDir, "scan").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, file.absolutePath))
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    btnShot.isEnabled = true
                    Toast.makeText(this@CaptureActivity, "拍摄失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
