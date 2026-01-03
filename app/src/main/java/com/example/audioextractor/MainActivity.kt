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

        // Status inicial
        binding.tvStatus.text = "Conectando ao motor..."
        
        inicializarEAtualizar()

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Cole um link primeiro", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isInitialized) {
                Toast.makeText(this, "Aguarde o sistema ficar pronto...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            iniciarDownload(url)
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { controlarPlayer(it) }
        }
    }

    private fun inicializarEAtualizar() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                
                withContext(Dispatchers.Main) { 
                    binding.tvStatus.text = "Verificando atualizações..." 
                }
                
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                } catch (e: Exception) {
                    Log.e("Extrator", "Sem internet para atualizar", e)
                }

                isInitialized = true
                val versao = YoutubeDL.getInstance().version(applicationContext)
                
                runOnUiThread { 
                    binding.tvStatus.text = "Sistema v$versao Pronto." 
                }
            } catch (e: Exception) {
                runOnUiThread { binding.tvStatus.text = "Erro: ${e.message}" }
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

        // UI de carregamento
        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.text = "PROCESSANDO..."
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Iniciando download..."
        
        // Esconde player antigo
        binding.cardPlayer.visibility = View.GONE
        mediaPlayer?.release()
        mediaPlayer = null

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

                request.addOption("--no-check-certificate")
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")

                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                    runOnUiThread { 
                        binding.tvStatus.text = "Baixando: $progress%" 
                    }
                }

                val arquivoBaixado = tempDir.listFiles()?.firstOrNull()

                if (arquivoBaixado != null) {
                    val uri = salvarDownloads(arquivoBaixado)
                    runOnUiThread {
                        if (uri != null) {
                            lastDownloadedUri = uri
                            // Sucesso: Mostra o Player
                            binding.tvStatus.text = "Download Completo"
                            binding.tvFileName.text = arquivoBaixado.name
                            binding.tvFileName.isSelected = true // Para o texto correr se for grande
                            binding.cardPlayer.visibility = View.VISIBLE
                            
                            binding.btnDownload.text = "BAIXAR OUTRO"
                            binding.etUrl.text.clear()
                            
                            mostrarAlerta("Sucesso", "Áudio salvo na pasta Downloads!")
                        } else {
                            binding.tvStatus.text = "Erro ao salvar"
                        }
                    }
                } else {
                    throw Exception("Arquivo não gerado")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvStatus.text = "Falha no download"
                    mostrarAlerta("Erro", e.message ?: "Desconhecido")
                }
            } finally {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                    if (binding.btnDownload.text == "PROCESSANDO...") binding.btnDownload.text = "BAIXAR ÁUDIO"
                }
            }
        }
    }

    private fun controlarPlayer(uri: Uri) {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    prepare()
                    start()
                    setOnCompletionListener {
                        binding.btnPlay.text = "▶"
                    }
                }
                binding.btnPlay.text = "⏸" // Pause icon
            } else {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                    binding.btnPlay.text = "▶"
                } else {
                    mediaPlayer!!.start()
                    binding.btnPlay.text = "⏸"
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao tocar", Toast.LENGTH_SHORT).show()
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

    private fun mostrarAlerta(titulo: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}


