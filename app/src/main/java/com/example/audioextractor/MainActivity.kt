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
            // Verificação de Segurança
            if (!isLibInitialized) {
                mostrarErro("Aguarde", "O sistema ainda está iniciando. Tente em 5 segundos.")
                return@setOnClickListener
            }

            // Permissões para Android 9 ou inferior
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
                // Tenta inicializar. Se falhar, captura o erro.
                YoutubeDL.getInstance().init(applicationContext)
                // Atualização automática das definições (importante para sites que mudam muito)
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                } catch (e: Exception) {
                    // Se falhar a atualização (sem internet), segue com a versão embarcada
                }
                
                isLibInitialized = true
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Pronto para baixar."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Erro na inicialização."
                    mostrarErro("Erro Crítico", "Não foi possível iniciar o motor de download.\n\nMotivo: ${e.message}")
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
                
                // Limpa arquivos anteriores
                tempDir.listFiles()?.forEach { it.delete() }

                val request = YoutubeDLRequest(url)
                request.addOption("-x") // Extrair áudio
                request.addOption("--audio-format", "m4a")
                request.addOption("--no-playlist")
                request.addOption("--no-check-certificate") // Ignora erros de SSL
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/91.0.4472.124")

                withContext(Dispatchers.Main) { binding.tvStatus.text = "Baixando áudio..." }
                
                // Executa
                YoutubeDL.getInstance().execute(request) { progress, _, _ -> 
                    // Progresso
                }

                // Procura o arquivo gerado
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
                            mostrarErro("Erro ao Salvar", "O arquivo baixou mas não consegui mover para a Galeria.")
                        }
                    }
                } else {
                    throw Exception("O download finalizou mas o arquivo de áudio não foi encontrado.")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Falha."
                    mostrarErro("Falha no Download", "Ocorreu um erro:\n${e.message}\n\nTente outro link.")
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Audio_${System.currentTimeMillis()}.m4a")
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


