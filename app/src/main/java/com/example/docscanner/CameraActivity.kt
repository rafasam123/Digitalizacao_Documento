package com.example.docscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.docscanner.databinding.ActivityCameraBinding
import org.opencv.core.Point
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val documentProcessor = DocumentProcessor()
    private var capturedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Verifica as permissões da câmera ao iniciar
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Configura os listeners de clique para os botões
        binding.captureButton.setOnClickListener { takePhoto() }
        binding.confirmButton.setOnClickListener { confirmCrop() }
        binding.scanNewButton.setOnClickListener { resetToCameraMode() }
        binding.saveButton.setOnClickListener {
            // A lógica para salvar o arquivo final entraria aqui
            Toast.makeText(this, "Funcionalidade de salvar ainda não implementada.", Toast.LENGTH_SHORT).show()
        }
    }

    // Inicia e configura a câmera para exibir o preview
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().setTargetRotation(binding.viewFinder.display.rotation).build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Falha ao iniciar a câmera", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Captura a foto e inicia o processo de edição
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    capturedBitmap = image.toBitmap()
                    image.close()
                    // Passa para a thread principal para modificar a UI
                    runOnUiThread { capturedBitmap?.let { enterEditMode(it) } }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Falha ao capturar imagem: ${exception.message}", exception)
                }
            }
        )
    }

    // Prepara a UI para o modo de edição manual
    private fun enterEditMode(bitmap: Bitmap) {
        // 1. Esconde a UI da câmera
        binding.viewFinder.visibility = View.GONE
        binding.captureButton.visibility = View.GONE
        binding.finalActionsLayout.visibility = View.GONE

        // 2. Tenta encontrar os cantos automaticamente
        val corners = documentProcessor.findDocumentCorners(bitmap)
        val initialPoints: Array<PointF>

        if (corners != null) {
            Toast.makeText(this, "Cantos detectados! Ajuste se necessário.", Toast.LENGTH_SHORT).show()
            initialPoints = corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
        } else {
            Toast.makeText(this, "Detecção falhou. Ajuste manual.", Toast.LENGTH_SHORT).show()
            val margin = 100f // Uma margem para os pontos não ficarem nos cantos exatos da imagem
            initialPoints = arrayOf(
                PointF(margin, margin),
                PointF(bitmap.width.toFloat() - margin, margin),
                PointF(bitmap.width.toFloat() - margin, bitmap.height.toFloat() - margin),
                PointF(margin, bitmap.height.toFloat() - margin)
            )
        }

        // 3. Mostra a UI de edição
        binding.cropView.setImageBitmap(bitmap)
        binding.cropView.setPoints(initialPoints) // Define os pontos e ativa o modo de edição na CropView
        binding.cropView.visibility = View.VISIBLE
        binding.confirmButton.visibility = View.VISIBLE
    }

    // Chamado quando o usuário clica em "Confirmar Recorte"
    private fun confirmCrop() {
        val finalPoints = binding.cropView.getPoints()
        val originalBitmap = capturedBitmap

        if (finalPoints != null && originalBitmap != null) {
            val opencvPoints = finalPoints.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()

            // Chama a função para recortar e aplicar os filtros
            val finalBitmap = documentProcessor.warpAndFilter(originalBitmap, opencvPoints)

            // 1. Desliga o modo de edição na CropView para remover as bordas verdes
            binding.cropView.setEditable(false)

            // 2. Mostra o resultado final na mesma view
            binding.cropView.setImageBitmap(finalBitmap)

            // 3. Esconde o botão de confirmar e mostra os botões de ação final
            binding.confirmButton.visibility = View.GONE
            binding.finalActionsLayout.visibility = View.VISIBLE

            Toast.makeText(this, "Digitalização Concluída!", Toast.LENGTH_SHORT).show()
        }
    }

    // Restaura a UI para o estado inicial da câmera
    private fun resetToCameraMode() {
        // Esconde a UI de edição/resultado
        binding.cropView.visibility = View.GONE
        binding.finalActionsLayout.visibility = View.GONE

        // Mostra a UI da câmera
        binding.viewFinder.visibility = View.VISIBLE
        binding.captureButton.visibility = View.VISIBLE

        // Limpa a imagem capturada para liberar memória
        capturedBitmap = null
    }

    // Código padrão para o resultado do pedido de permissão
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissão de câmera não concedida.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // Função auxiliar para verificar se a permissão foi concedida
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // Garante que o processo da câmera seja finalizado ao fechar o app
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // Constantes usadas na classe
    companion object {
        private const val TAG = "DocScanner"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}