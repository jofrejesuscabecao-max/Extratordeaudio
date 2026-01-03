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

        inicializarSistema()

        binding.btnDownload.setOnClickListener {
            if (!isInitialized) {
                inicializarSistema()
                return@setOnClickListener
            }
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) iniciarDownload(url)
            else Toast.makeText(this, "Cole um link válido", Toast.LENGTH_SHORT).show()
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { tocarAudio(it) }
        }
    }

    private fun inicializarSistema() {
        binding.tvStatus.text = "Carregando..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                try { YoutubeDL.getInstance().updateYoutubeDL(applicationContext) } catch (e: Exception) {}
                isInitialized = true
                runOnUiThread { binding.tvStatus.text = "Pronto." }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvStatus.text = "Erro init"
                    mostrarAlerta("Erro", e.message ?: "Desconhecido")
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
                
                val ffmpegLib = File(applicationContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
                if (ffmpegLib.exists()) request.addOption("--ffmpeg-location", ffmpegLib.absolutePath)
                else {
                    val f = File(applicationContext.filesDir, "ffmpeg")
                    if (f.exists()) request.addOption("--ffmpeg-location", f.absolutePath)
                }

                // --- BYPASS SUPREMO (IOS) ---
                // Mudamos de 'android' (que exige token) para 'ios' (que geralmente passa direto)
                request.addOption("--extractor-args", "youtube:player_client=ios")
                
                // Força IPv4 (Essencial para evitar bloqueio de IP de servidor)
                request.addOption("--force-ipv4")
                request.addOption("--no-check-certificate")
                
                // Pede o melhor áudio, mas se falhar, aceita o pior (melhor ter áudio ruim que nenhum)
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")

                runOnUiThread { binding.tvStatus.text = "Baixando..." }

                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                    runOnUiThread { binding.tvStatus.text = "Baixando: $progress%" }
                }

                val arquivoBaixado = tempDir.listFiles()?.firstOrNull()

                if (arquivoBaixado != null) {
                    val uri = salvarDownloads(arquivoBaixado)
                    runOnUiThread {
                        if (uri != null) {
                            lastDownloadedUri = uri
                            binding.tvStatus.text = "Sucesso!"
                            binding.btnPlay.isEnabled = true
                            mostrarAlerta("Sucesso", "Salvo em Downloads!")
                        } else {
                            binding.tvStatus.text = "Erro salvar"
                        }
                    }
                } else {
                    throw Exception("Arquivo não gerado")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvStatus.text = "Falha"
                    mostrarAlerta("Erro Download", e.message ?: "Desconhecido")
                }
            } finally {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun salvarDownloads(arquivo: File): Uri? {
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Audio_${System.currentTimeMillis()}.${arquivo.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/${arquivo.extension}") 
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            uri?.let {
                contentResolver.openOutputStream(it).use { output ->
                    FileInputStream(arquivo).use { input -> input.copyTo(output!!) }
                }
            }
            uri
        } catch (e: Exception) { null }
    }

    private fun tocarAudio(uri: Uri) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                prepare()
                start()
            }
        } catch (e: Exception) { Toast.makeText(this, "Erro play", Toast.LENGTH_SHORT).show() }
    }

    private fun mostrarAlerta(titulo: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}


