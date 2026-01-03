// Arquivo: app/src/main/java/com/example/audioextractor/MainActivity.kt
package com.example.audioextractor

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Inicializa a biblioteca (obrigatório)
        initLibrary()

        // 2. Configura botão de Download
        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startDownload(url)
            } else {
                Toast.makeText(this, "Cole um link primeiro", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Configura botão de Play
        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { uri -> playAudio(uri) }
        }
    }

    private fun initLibrary() {
        try {
            YoutubeDL.getInstance().init(applicationContext)
            // ffmpeg é necessário para extrair áudio de alguns formatos
            YoutubeDL.getInstance().init(applicationContext) 
            binding.tvStatus.text = "Sistema pronto."
        } catch (e: Exception) {
            binding.tvStatus.text = "Erro ao iniciar biblioteca: ${e.message}"
            e.printStackTrace()
        }
    }

    private fun startDownload(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Iniciando download..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Pasta temporária interna do app
                val tempDir = File(cacheDir, "temp_downloads")
                if (!tempDir.exists()) tempDir.mkdirs()
                
                // Limpa lixo anterior
                tempDir.listFiles()?.forEach { it.delete() }

                // Configura o pedido ao yt-dlp
                val request = YoutubeDLRequest(url)
                request.addOption("-x") // Extrair áudio
                request.addOption("--audio-format", "m4a") // Melhor formato para Android
                request.addOption("--no-playlist")
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                
                // User Agent para evitar bloqueios do TikTok/Insta
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

                // Executa
                withContext(Dispatchers.Main) { binding.tvStatus.text = "Baixando e Convertendo..." }
                
                val response = YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                   // Opcional: atualizar progresso
                }

                // Localiza o arquivo gerado
                val downloadedFile = tempDir.listFiles()
                    ?.filter { it.extension == "m4a" }
                    ?.maxByOrNull { it.lastModified() }

                if (downloadedFile != null) {
                    // Move para pasta pública
                    val publicUri = moveFileToDownloads(downloadedFile)
                    
                    withContext(Dispatchers.Main) {
                        if (publicUri != null) {
                            lastDownloadedUri = publicUri
                            binding.tvStatus.text = "Sucesso! Salvo em Downloads."
                            binding.btnPlay.isEnabled = true
                            binding.etUrl.text?.clear()
                            Toast.makeText(this@MainActivity, "Áudio salvo!", Toast.LENGTH_LONG).show()
                        } else {
                            binding.tvStatus.text = "Erro ao salvar na galeria."
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { binding.tvStatus.text = "Erro: Arquivo não encontrado após download." }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Falha: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                }
            }
        }
    }

    // Função para salvar na pasta Downloads oficial do Android
    private fun moveFileToDownloads(tempFile: File): Uri? {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, tempFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4") // m4a é container mp4
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
                // tempFile.delete() // Opcional: apagar o temp
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
            Toast.makeText(this, "Tocando...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao tocar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}

