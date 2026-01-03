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

        // Passo 1: Iniciar e tentar atualizar o motor
        inicializarEAtualizar()

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Cole um link válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isInitialized) {
                Toast.makeText(this, "Aguarde o motor ficar pronto...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            iniciarDownload(url)
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { controlarPlayer(it) }
        }
    }

    private fun inicializarEAtualizar() {
        binding.tvStatus.text = "Preparando sistema..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Inicializa a versão base
                YoutubeDL.getInstance().init(applicationContext)
                
                // 2. ATUALIZAÇÃO CRÍTICA
                // Verifica e baixa a versão mais recente do yt-dlp do GitHub
                withContext(Dispatchers.Main) { binding.tvStatus.text = "Atualizando motor (Necessário)..." }
                
                try {
                    // Isso baixa a versão mais nova que corrige o erro 403
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                } catch (e: Exception) {
                    Log.e("Extrator", "Falha ao atualizar (pode ser sem internet)", e)
                }

                isInitialized = true
                val versao = YoutubeDL.getInstance().version(applicationContext)
                
                runOnUiThread { 
                    binding.tvStatus.text = "Pronto (v$versao). Cole o link." 
                }
            } catch (e: Exception) {
                runOnUiThread { binding.tvStatus.text = "Erro init: ${e.message}" }
            }
        }
    }

    private fun iniciarDownload(url: String) {
        // Verifica permissões
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
                
                // --- CONFIGURAÇÃO PÓS-ATUALIZAÇÃO ---
                // Com o motor atualizado, usamos o cliente padrão 'android' que tem melhor qualidade
                // Não precisamos mais de bypass maluco se o motor for novo.
                
                val ffmpegLib = File(applicationContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
                if (ffmpegLib.exists()) request.addOption("--ffmpeg-location", ffmpegLib.absolutePath)
                else {
                    val f = File(applicationContext.filesDir, "ffmpeg")
                    if (f.exists()) request.addOption("--ffmpeg-location", f.absolutePath)
                }

                request.addOption("--no-check-certificate")
                
                // Garante formato m4a (áudio eficiente)
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                
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
                            binding.tvFileName.text = arquivoBaixado.name
                            binding.cardPlayer.visibility = View.VISIBLE
                            binding.btnPlay.text = "▶  TOCAR AGORA"
                            binding.btnPlay.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                            mostrarAlerta("Sucesso", "Download concluído!")
                        } else {
                            binding.tvStatus.text = "Erro ao salvar"
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

    // --- FUNÇÕES AUXILIARES (Salvar, Tocar, Alerta) ---

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

    private fun controlarPlayer(uri: Uri) {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    prepare()
                    start()
                    setOnCompletionListener {
                        binding.btnPlay.text = "▶  TOCAR NOVAMENTE"
                        binding.btnPlay.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    }
                }
                binding.btnPlay.text = "⏸  PAUSAR"
                binding.btnPlay.setBackgroundColor(android.graphics.Color.parseColor("#E91E63"))
            } else {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                    binding.btnPlay.text = "▶  CONTINUAR"
                    binding.btnPlay.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
                } else {
                    mediaPlayer!!.start()
                    binding.btnPlay.text = "⏸  PAUSAR"
                    binding.btnPlay.setBackgroundColor(android.graphics.Color.parseColor("#E91E63"))
                }
            }
        } catch (e: Exception) { Toast.makeText(this, "Erro player", Toast.LENGTH_SHORT).show() }
    }

    private fun mostrarAlerta(titulo: String, msg: String) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(msg).setPositiveButton("OK", null).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}


