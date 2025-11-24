package com.example.docscanner

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.docscanner.databinding.ActivityCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.core.Point
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private val documentProcessor = DocumentProcessor()
    private var capturedBitmap: Bitmap? = null
    private var currentFinalBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) { startCamera() } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.confirmButton.setOnClickListener { confirmCrop() }
        binding.scanNewButton.setOnClickListener { resetToCameraMode() }
        binding.saveButton.setOnClickListener { initiateSmartSave() }
        
        binding.filterNoneButton.setOnClickListener { updatePreviewWithFilter(FilterType.NONE) }
        binding.filterBwButton.setOnClickListener { updatePreviewWithFilter(FilterType.BLACK_AND_WHITE) }
        binding.filterSharpenButton.setOnClickListener { updatePreviewWithFilter(FilterType.SHARPEN) }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setTargetRotation(binding.viewFinder.display.rotation).build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try { cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture) } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                capturedBitmap = image.toBitmap()
                image.close()
                runOnUiThread { capturedBitmap?.let { enterEditMode(it) } }
            }
            override fun onError(exception: ImageCaptureException) {}
        })
    }

    private fun enterEditMode(bitmap: Bitmap) {
        binding.viewFinder.visibility = View.GONE
        binding.captureButton.visibility = View.GONE
        binding.finalActionsLayout.visibility = View.GONE
        binding.filterButtonsLayout.visibility = View.GONE

        val corners = documentProcessor.findDocumentCorners(bitmap)
        val points = if (corners != null) {
            corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
        } else {
            val m = 100f
            arrayOf(PointF(m, m), PointF(bitmap.width-m, m), PointF(bitmap.width-m, bitmap.height-m), PointF(m, bitmap.height-m))
        }

        binding.cropView.setImageBitmap(bitmap)
        binding.cropView.setPoints(points)
        binding.cropView.visibility = View.VISIBLE
        binding.confirmButton.visibility = View.VISIBLE
    }

    private fun confirmCrop() {
        binding.cropView.setEditable(false)
        binding.confirmButton.visibility = View.GONE
        binding.filterButtonsLayout.visibility = View.VISIBLE
        binding.finalActionsLayout.visibility = View.VISIBLE
        updatePreviewWithFilter(FilterType.BLACK_AND_WHITE)
        Toast.makeText(this, "Recorte confirmado!", Toast.LENGTH_SHORT).show()
    }

    private fun updatePreviewWithFilter(type: FilterType) {
        val points = binding.cropView.getPoints()
        val original = capturedBitmap
        if (points != null && original != null) {
            val opencvPoints = points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
            currentFinalBitmap = documentProcessor.warpAndFilter(original, opencvPoints, type)
            binding.cropView.setImageBitmap(currentFinalBitmap)
        }
    }

    private fun resetToCameraMode() {
        binding.cropView.visibility = View.GONE
        binding.filterButtonsLayout.visibility = View.GONE
        binding.finalActionsLayout.visibility = View.GONE
        binding.viewFinder.visibility = View.VISIBLE
        binding.captureButton.visibility = View.VISIBLE
        capturedBitmap = null
        currentFinalBitmap = null
    }

    private fun initiateSmartSave() {
        val bitmapToSave = currentFinalBitmap ?: return
        Toast.makeText(this, "Processando OCR...", Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmapToSave, 0))
            .addOnSuccessListener { saveFiles(bitmapToSave, it.text) }
            .addOnFailureListener { saveFiles(bitmapToSave, "Erro OCR") }
    }

    private fun saveFiles(bitmap: Bitmap, text: String) {
        val name = "DOC_${System.currentTimeMillis()}"
        try {
            saveJpg(bitmap, name)
            savePdf(bitmap, name, text)
            saveMarkdown(name, text)
            Toast.makeText(this, "Salvo com sucesso!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Erro ao salvar", Toast.LENGTH_SHORT).show() }
    }

    private fun saveJpg(bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocScanner")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, s) } }
    }

    private fun savePdf(bitmap: Bitmap, name: String, text: String) {
        val pdfDocument = PdfDocument()
        val pageWidth = 595; val pageHeight = 842
        val page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val canvas = page.canvas
        val margin = 40f
        var y = margin

        val titlePaint = TextPaint().apply { color = Color.BLACK; textSize = 24f; isFakeBoldText = true }
        canvas.drawText("Documento Digitalizado", margin, y + 24f, titlePaint)
        y += 50f

        val availableWidth = pageWidth - (2 * margin)
        val scale = availableWidth / bitmap.width
        val scaledHeight = (bitmap.height * scale).coerceAtMost(400f)
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(margin, y, margin + availableWidth, y + scaledHeight), null)
        y += scaledHeight + 30f

        val bodyPaint = TextPaint().apply { color = Color.DKGRAY; textSize = 12f }
        val textLayout = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, (pageWidth - 2 * margin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false) // Correção aplicada aqui
            .build()
        
        canvas.save()
        canvas.translate(margin, y)
        textLayout.draw(canvas)
        canvas.restore()

        pdfDocument.finishPage(page)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocScanner")
        }
        val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> pdfDocument.writeTo(s) } }
        pdfDocument.close()
    }

    private fun saveMarkdown(name: String, text: String) {
        val mdContent = "# $name\n\n## OCR\n$text"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.md")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/DocScanner")
        }
        val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> s.write(mdContent.toByteArray()) } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, p: Array<String>, g: IntArray) {
        super.onRequestPermissionsResult(requestCode, p, g)
        if (requestCode == 10 && allPermissionsGranted()) startCamera()
    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED }
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
