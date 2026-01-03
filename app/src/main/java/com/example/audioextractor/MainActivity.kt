package com.example.audioextractor

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
    private var isInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicia o sistema
        inicializarSistema()

        binding.btnDownload.setOnClickListener {
            if (!isInitialized) {
                inicializarSistema()
                return@setOnClickListener
            }

            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                iniciarDownload(url)
            } else {
                Toast.makeText(this, "Cole um link válido", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { tocarAudio(it) }
        }
    }

    private fun inicializarSistema() {
        binding.tvStatus.text = "Configurando motor..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Inicializa a biblioteca (extrai os arquivos)
                YoutubeDL.getInstance().init(applicationContext)
                
                // Tenta atualizar (opcional)
                try { YoutubeDL.getInstance().updateYoutubeDL(applicationContext) } catch (e: Exception) {}

                isInitialized = true
                
                runOnUiThread {
                    binding.tvStatus.text = "Pronto. Cole o link."
                }
            } catch (e: Exception) {
                Log.e("AudioExtractor", "Erro init", e)
                runOnUiThread {
                    binding.tvStatus.text = "Erro no motor."
                    mostrarAlerta("Falha de Inicialização", 
                        "O aplicativo não conseguiu preparar os arquivos.\n\nErro: ${e.message}")
                }
            }
        }
    }

    private fun iniciarDownload(url: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                return
            }
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Iniciando..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "temp_dl")
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                val request = YoutubeDLRequest(url)
                
                // --- CORREÇÃO SEGURA: BUSCA MANUAL DO FFMPEG ---
                // Não usamos mais a função privada. Nós mesmos achamos o arquivo.
                val ffmpegFile = encontrarFfmpeg(applicationContext)
                if (ffmpegFile != null) {
                    // Passa o caminho exato para o comando
                    request.addOption("--ffmpeg-location", ffmpegFile.absolutePath)
                }

                // Opções para evitar erros de JavaScript e Formato
                request.addOption("-x")
                request.addOption("--audio-format", "m4a")
                request.addOption("--no-playlist")
                request.addOption("--no-check-certificate")
                // Força usar apenas ipv4 para evitar bloqueios do Google
                request.addOption("--force-ipv4")
                // Tenta extrair o melhor áudio disponível
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/118.0.0.0 Safari/537.36")

                runOnUiThread { binding.tvStatus.text = "Baixando... aguarde." }

                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                    runOnUiThread {
                        binding.tvStatus.text = "Baixando: $progress%"
                    }
                }

                val arquivoBaixado = tempDir.listFiles()?.firstOrNull { it.extension == "m4a" || it.extension == "mp4" }

                if (arquivoBaixado != null) {
                    val uri = salvarDownloads(arquivoBaixado)
                    runOnUiThread {
                        if (uri != null) {
                            lastDownloadedUri = uri
                            binding.tvStatus.text = "Sucesso! Salvo em Downloads."
                            binding.btnPlay.isEnabled = true
                            mostrarAlerta("Concluído", "Áudio salvo com sucesso!")
                        } else {
                            binding.tvStatus.text = "Erro ao salvar."
                        }
                    }
                } else {
                    throw Exception("O arquivo não foi encontrado após o processo.")
                }

            } catch (e: Exception) {
                Log.e("AudioExtractor", "Erro download", e)
                runOnUiThread {
                    binding.tvStatus.text = "Falha."
                    mostrarAlerta("Erro no Download", "Detalhes: ${e.message}")
                }
            } finally {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                }
            }
        }
    }

    // --- FUNÇÃO "CAÇA-ARQUIVO" ---
    // Varre as pastas internas do app procurando onde o ffmpeg se escondeu
    private fun encontrarFfmpeg(context: Context): File? {
        try {
            // Locais comuns onde a biblioteca descompacta os arquivos
            val locaisPossiveis = listOf(
                File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so"),
                File(context.noBackupFilesDir, "youtubedl-android/ffmpeg"),
                File(context.noBackupFilesDir, "ffmpeg"),
                File(context.filesDir, "ffmpeg")
            )

            // Tenta achar um que exista
            for (arquivo in locaisPossiveis) {
                if (arquivo.exists()) return arquivo
            }
            
            // Busca recursiva se não achar nos locais padrão
            return context.noBackupFilesDir.walkTopDown().find { it.name.contains("ffmpeg") }
        } catch (e: Exception) {
            return null
        }
    }

    private fun salvarDownloads(arquivo: File): Uri? {
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Audio_${System.currentTimeMillis()}.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        return try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            uri?.let {
                contentResolver.openOutputStream(it).use { output ->
                    FileInputStream(arquivo).use { input ->
                        input.copyTo(output!!)
                    }
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun tocarAudio(uri: Uri) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao tocar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostrarAlerta(titulo: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}


