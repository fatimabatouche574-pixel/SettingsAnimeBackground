package com.local.settingsanimebackground.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.local.settingsanimebackground.config.ConfigRepository
import com.local.settingsanimebackground.config.ModuleConfig
import com.local.settingsanimebackground.databinding.ActivityMainBinding
import com.local.settingsanimebackground.image.ImageImporter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ConfigRepository
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewGeneration = AtomicInteger()
    private var loadingUi = true
    private var previewBitmap: Bitmap? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 部分文档提供方不支持持久化授权；当前授权仍可完成一次导入。
        }
        importImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ConfigRepository(this)
        setupListeners()
        render(repository.read())
        binding.root.post { loadingUi = false }
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) {
            loadingUi = true
            render(repository.read())
            binding.root.post { loadingUi = false }
        }
    }

    override fun onDestroy() {
        previewGeneration.incrementAndGet()
        worker.shutdownNow()
        binding.previewImage.setImageDrawable(null)
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewBitmap = null
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            updateUnlessLoading { enabled = checked }
        }
        binding.switchClearOpaque.setOnCheckedChangeListener { _, checked ->
            updateUnlessLoading { clearOpaqueBackgrounds = checked }
        }
        binding.switchCardTransparency.setOnCheckedChangeListener { _, checked ->
            binding.seekCardAlpha.isEnabled = checked
            updateUnlessLoading { cardTransparencyEnabled = checked }
        }
        binding.switchExtendBars.setOnCheckedChangeListener { _, checked ->
            binding.spinnerStatusIcon.isEnabled = checked
            updateUnlessLoading { extendIntoSystemBars = checked }
        }
        binding.switchDiagnostic.setOnCheckedChangeListener { _, checked ->
            updateUnlessLoading { diagnosticLogging = checked }
        }

        bindSeekBar(binding.seekOpacity, onProgress = {
            binding.textOpacityValue.text = "背景透明度：${it + 10}%"
        }) { progress ->
            updateUnlessLoading { opacity = (progress + 10) / 100f }
        }
        bindSeekBar(binding.seekDim, onProgress = {
            binding.textDimValue.text = "遮罩强度：$it%"
        }) { progress ->
            updateUnlessLoading { dimAmount = progress / 100f }
        }
        bindSeekBar(binding.seekBlur, onProgress = {
            binding.textBlurValue.text = "模糊强度：${it}dp"
        }) { progress ->
            updateUnlessLoading { blurRadius = progress.toFloat() }
        }
        bindSeekBar(binding.seekCardAlpha, onProgress = {
            binding.textCardAlphaValue.text = "卡片透明度：${it + 10}%"
        }) { progress ->
            updateUnlessLoading { cardAlpha = (progress + 10) / 100f }
        }

        binding.spinnerScale.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreviewScale(position)
                updateUnlessLoading { scaleType = position }
            }
        }
        binding.spinnerStatusIcon.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateUnlessLoading { statusBarIconMode = position }
            }
        }

        binding.buttonChooseImage.setOnClickListener {
            openDocument.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
        }
        binding.buttonDeleteImage.setOnClickListener { confirmDeleteBackground() }
        binding.buttonRestore.setOnClickListener { confirmRestoreDefaults() }
        binding.buttonOpenSettings.setOnClickListener { openSystemSettings() }
        binding.buttonRestartSettings.setOnClickListener { confirmRestartSettings() }
    }

    private fun render(config: ModuleConfig) {
        binding.switchEnabled.isChecked = config.enabled
        binding.seekOpacity.progress = ModuleConfig.percent(config.opacity) - 10
        binding.seekDim.progress = ModuleConfig.percent(config.dimAmount)
        binding.seekBlur.progress = config.blurRadius.toInt()
        binding.spinnerScale.setSelection(config.scaleType)
        binding.switchClearOpaque.isChecked = config.clearOpaqueBackgrounds
        binding.switchCardTransparency.isChecked = config.cardTransparencyEnabled
        binding.seekCardAlpha.progress = ModuleConfig.percent(config.cardAlpha) - 10
        binding.seekCardAlpha.isEnabled = config.cardTransparencyEnabled
        binding.switchExtendBars.isChecked = config.extendIntoSystemBars
        binding.spinnerStatusIcon.setSelection(config.statusBarIconMode)
        binding.spinnerStatusIcon.isEnabled = config.extendIntoSystemBars
        binding.switchDiagnostic.isChecked = config.diagnosticLogging

        binding.textOpacityValue.text = "背景透明度：${ModuleConfig.percent(config.opacity)}%"
        binding.textDimValue.text = "遮罩强度：${ModuleConfig.percent(config.dimAmount)}%"
        binding.textBlurValue.text = "模糊强度：${config.blurRadius.toInt()}dp"
        binding.textCardAlphaValue.text = "卡片透明度：${ModuleConfig.percent(config.cardAlpha)}%"
        updatePreviewScale(config.scaleType)
        loadPreviewAsync()
    }

    private fun loadPreviewAsync() {
        val file = repository.backgroundFile()
        val generation = previewGeneration.incrementAndGet()
        if (!file.isFile) {
            replacePreviewBitmap(null)
            binding.textNoImage.visibility = View.VISIBLE
            binding.buttonDeleteImage.isEnabled = false
            return
        }
        binding.textNoImage.visibility = View.GONE
        binding.buttonDeleteImage.isEnabled = true
        worker.execute {
            val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            mainHandler.post {
                if (generation != previewGeneration.get() || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                replacePreviewBitmap(bitmap)
                binding.textNoImage.visibility = if (bitmap == null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun importImage(uri: android.net.Uri) {
        setBusy(true, "正在处理图片…")
        worker.execute {
            try {
                val result = ImageImporter(applicationContext).import(uri)
                repository.backgroundChanged(result.luminance)
                mainHandler.post {
                    if (isDestroyed) return@post
                    setBusy(false, "导入成功：${result.width} × ${result.height}")
                    loadPreviewAsync()
                    toast("背景图片导入成功")
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    if (isDestroyed) return@post
                    setBusy(false, "导入失败")
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: "图片过大或格式不受支持"
                    AlertDialog.Builder(this)
                        .setTitle("导入失败")
                        .setMessage(detail)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun confirmDeleteBackground() {
        AlertDialog.Builder(this)
            .setTitle("删除背景")
            .setMessage("确定删除当前背景图片吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (repository.deleteBackground()) {
                    loadPreviewAsync()
                    binding.textStatus.text = "背景图片已删除"
                    toast("背景图片已删除")
                } else {
                    toast("删除失败，请稍后重试")
                }
            }
            .show()
    }

    private fun confirmRestoreDefaults() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认设置")
            .setMessage("所有显示参数将恢复默认值，背景图片不会被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                repository.restoreDefaults()
                loadingUi = true
                render(repository.read())
                binding.root.post { loadingUi = false }
                binding.textStatus.text = "已恢复默认设置"
            }
            .show()
    }

    private fun confirmRestartSettings() {
        AlertDialog.Builder(this)
            .setTitle("安全重启设置作用域")
            .setMessage("将强制停止系统设置进程，不会重启手机。随后会重新打开设置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> restartSettingsScope() }
            .show()
    }

    private fun restartSettingsScope() {
        setBusy(true, "正在重启系统设置…")
        worker.execute {
            val success = try {
                val process = ProcessBuilder(
                    "su",
                    "-c",
                    "am force-stop --user current com.android.settings",
                )
                    .redirectErrorStream(true)
                    .redirectOutput(File("/dev/null"))
                    .start()
                val finished = process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                finished && process.exitValue() == 0
            } catch (_: Exception) {
                false
            }
            mainHandler.post {
                if (isDestroyed) return@post
                setBusy(false, if (success) "系统设置已安全停止" else "未获得 Root 权限")
                if (success) openSystemSettings() else toast("操作失败，请确认已授予 Root 权限")
            }
        }
    }

    private fun replacePreviewBitmap(bitmap: Bitmap?) {
        val old = previewBitmap
        binding.previewImage.setImageDrawable(null)
        previewBitmap = bitmap
        if (bitmap != null) binding.previewImage.setImageBitmap(bitmap)
        if (old !== bitmap && old != null && !old.isRecycled) old.recycle()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            toast("无法打开系统设置")
        }
    }

    private fun updatePreviewScale(scaleType: Int) {
        binding.previewImage.scaleType = when (scaleType) {
            ModuleConfig.SCALE_FIT_CENTER -> android.widget.ImageView.ScaleType.FIT_CENTER
            ModuleConfig.SCALE_FIT_XY -> android.widget.ImageView.ScaleType.FIT_XY
            else -> android.widget.ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun updateUnlessLoading(block: ConfigRepository.MutableConfig.() -> Unit) {
        if (!loadingUi) repository.update(block)
    }

    private fun bindSeekBar(
        seekBar: SeekBar,
        onProgress: (Int) -> Unit,
        onCommit: (Int) -> Unit,
    ) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                onProgress(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) {
                onCommit(seekBar.progress)
            }
        })
    }

    private fun setBusy(busy: Boolean, status: String) {
        binding.buttonChooseImage.isEnabled = !busy
        binding.buttonRestartSettings.isEnabled = !busy
        binding.textStatus.text = status
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private abstract class SimpleItemSelectedListener : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        private const val ROOT_COMMAND_TIMEOUT_SECONDS = 8L
    }
}
