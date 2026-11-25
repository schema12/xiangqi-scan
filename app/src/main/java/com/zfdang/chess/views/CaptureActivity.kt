package com.zfdang.chess.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
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
 * 拍照识别摆谱（图二效果）：黑底相机预览 + 上下横线界定 3:4 取景框 +
 * 紫色「拍照识别」大按钮 + 右下角相册/截图入口。
 * 拍照或选图后统一交给 [ScanActivity] 识别。
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        /** 揭棋模式：拍照/相册后交给 ScanActivity 时须透传，否则会用普通16类模型丢失暗子 */
        const val EXTRA_JIEQI = "jieqi"
    }

    private lateinit var preview: androidx.camera.view.PreviewView
    private lateinit var btnShot: Button
    private var imageCapture: ImageCapture? = null
    private var jieqiMode = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "需要相机权限才能拍照识别", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            startActivity(Intent(this, ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_URI, it.toString())
                putExtra(ScanActivity.EXTRA_JIEQI, jieqiMode)
            })
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        jieqiMode = intent.getBooleanExtra(EXTRA_JIEQI, false)
        preview = findViewById(R.id.preview)
        btnShot = findViewById(R.id.btnShot)
        findViewById<TextView>(R.id.capHint).text = getString(R.string.cap_hint)
        btnShot.setOnClickListener { takePhoto() }
        findViewById<android.widget.ImageView>(R.id.btnAlbum).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

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
                    startActivity(Intent(this@CaptureActivity, ScanActivity::class.java).apply {
                        putExtra(ScanActivity.EXTRA_PATH, file.absolutePath)
                        putExtra(ScanActivity.EXTRA_JIEQI, jieqiMode)
                    })
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    btnShot.isEnabled = true
                    Toast.makeText(this@CaptureActivity, "拍摄失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
