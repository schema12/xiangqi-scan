package com.xiangqiscan.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xiangqiscan.app.capture.CaptureActivity
import com.xiangqiscan.app.databinding.ActivityMainBinding
import com.xiangqiscan.app.io.Delivery
import com.xiangqiscan.app.io.Prefs
import com.xiangqiscan.app.scan.ScanActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) startScan(uri)
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(CaptureActivity.EXTRA_PATH)
        if (path != null) startScan(Uri.fromFile(java.io.File(path)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnCamera.setOnClickListener {
            captureLauncher.launch(Intent(this, CaptureActivity::class.java))
        }
        binding.btnAlbum.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnOpenChess.setOnClickListener { openChess() }
        binding.btnSettings.setOnClickListener { showSettings() }
    }

    private fun startScan(uri: Uri) {
        val i = Intent(this, ScanActivity::class.java).setData(uri)
        startActivity(i)
    }

    private fun openChess() {
        val i = packageManager.getLaunchIntentForPackage(Delivery.CHESS_PKG)
        if (i != null) startActivity(i)
        else Toast.makeText(this, "未安装《中国象棋》(${Delivery.CHESS_PKG})", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        view.setPadding(pad, pad, pad, 0)

        val etSu = EditText(this).apply { hint = "root 命令（suu / su）"; setText(prefs.suCommand) }
        val etDir = EditText(this).apply { hint = "棋谱目标目录"; setText(prefs.targetDir) }
        view.addView(etSu)
        view.addView(etDir)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                prefs.suCommand = etSu.text.toString().trim().ifEmpty { "suu" }
                prefs.targetDir = etDir.text.toString().trim()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
