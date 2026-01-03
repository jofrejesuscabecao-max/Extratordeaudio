package com.example.audioextractor

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.audioextractor.databinding.ActivityMainBinding
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastDownloadedUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isLibInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Inicializa em background para não travar a tela
        inicializarBiblioteca()

        binding.btnDownload.setOnClickListener {
            if (!isLibInitialized) {
                mostrarErro("Aguarde", "O sistema ainda está iniciando. Tente em 5 segundos.")
                return@setOnClickListener
            }

            // Verifica permissão para Android antigo
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                    return@setOnClickListener
                }
            }

            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startDownload(url)
            } else {
                Toast.makeText(this, "Cole um link válido", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { uri -> playAudio(uri) }
        }
    }

    private fun inicializarBiblioteca() {
        binding.tvStatus.text = "Iniciando sistema..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext) // Tenta atualizar as definições
                isLibInitialized = true
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Pronto para baixar."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Erro na inicialização."
                    mostrarErro("Erro Crítico", "Não foi possível iniciar o motor de download: ${e.message}")
                }
            }
        }
    }

    private fun startDownload(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Processando link..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "temp_downloads")
                if (!tempDir.exists()) tempDir.mkdirs()
                
                // Limpa lixo anterior
                tempDir.listFiles()?.forEach { it.delete() }

                // Configuração agressiva para compatibilidade
                val request = YoutubeDLRequest(url)
                request.addOption("-x") // Extrair áudio
                request.addOption("--audio-format", "m4a")
                request.addOption("--no-playlist")
                request.addOption("--no-check-certificate") // Ignora erros de SSL
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                
                // Headers para simular navegador de PC
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

                withContext(Dispatchers.Main) { binding.tvStatus.text = "Baixando áudio..." }
                
                // Executa o download
                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                     // Opcional: Log de progresso
                }

                // Encontra o arquivo
                val downloadedFile = tempDir.listFiles()
                    ?.filter { it.extension == "m4a" || it.extension == "mp4" || it.extension == "webm" }
                    ?.maxByOrNull { it.lastModified() }

                if (downloadedFile != null) {
                    val publicUri = moveFileToDownloads(downloadedFile)
                    
                    withContext(Dispatchers.Main) {
                        if (publicUri != null) {
                            lastDownloadedUri = publicUri
                            binding.tvStatus.text = "Sucesso!"
                            binding.btnPlay.isEnabled = true
                            binding.etUrl.text?.clear()
                            mostrarSucesso("Download Concluído", "Salvo na pasta Downloads.")
                        } else {
                            mostrarErro("Erro ao Salvar", "O arquivo foi baixado mas falhou ao mover para a galeria.")
                        }
                    }
                } else {
                    throw Exception("O download parece ter funcionado, mas nenhum arquivo de áudio foi gerado.")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Falha."
                    mostrarErro("Falha no Download", "Erro: ${e.message}\n\nTente outro link.")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun moveFileToDownloads(tempFile: File): Uri? {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Audio_${System.currentTimeMillis()}.m4a") // Nome único
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        return try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri).use { output ->
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(output!!)
                    }
                }
                uri
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun playAudio(uri: Uri) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                prepare()
                start()
            }
            Toast.makeText(this, "Reproduzindo...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao tocar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun mostrarErro(titulo: String, mensagem: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensagem)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun mostrarSucesso(titulo: String, mensagem: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensagem)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}


