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
    // Variável para controlar se o motor está pronto
    private var estadoMotor = "Carregando..." 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Tenta iniciar assim que abre
        iniciarMotor()

        binding.btnDownload.setOnClickListener {
            if (estadoMotor != "Pronto") {
                // Se o usuário clicar antes, tenta iniciar de novo
                iniciarMotor()
                Toast.makeText(this, "Aguarde: $estadoMotor", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Permissões (Só para Android 9 ou menos)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                    return@setOnClickListener
                }
            }

            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                fazerDownload(url)
            } else {
                Toast.makeText(this, "Cole um link primeiro", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { uri -> tocarAudio(uri) }
        }
    }

    private fun iniciarMotor() {
        binding.tvStatus.text = "Iniciando motor..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Tenta inicializar a biblioteca
                YoutubeDL.getInstance().init(applicationContext)
                
                // Marca como pronto
                estadoMotor = "Pronto"
                
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Motor Pronto. Pode baixar."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                estadoMotor = "Erro: ${e.message}"
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Falha ao iniciar motor."
                    mostrarAlerta("Erro Crítico no Motor", 
                        "O sistema não conseguiu carregar os componentes.\n\n" +
                        "Motivo Técnico: ${e.message}\n\n" +
                        "Tente reiniciar o aplicativo ou limpar o cache.")
                }
            }
        }
    }

    private fun fazerDownload(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Baixando..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Pasta temporária segura
                val pastaTemp = File(cacheDir, "temp_dl")
                if (!pastaTemp.exists()) pastaTemp.mkdirs()
                
                // Limpa arquivos velhos
                pastaTemp.listFiles()?.forEach { it.delete() }

                val request = YoutubeDLRequest(url)
                request.addOption("-x") // Extrair áudio
                request.addOption("--audio-format", "m4a")
                request.addOption("--no-playlist")
                request.addOption("--no-check-certificate")
                // Salva com nome genérico para evitar erros de caracteres estranhos
                request.addOption("-o", "${pastaTemp.absolutePath}/audio_temp.%(ext)s")
                
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/115.0.0.0 Safari/537.36")

                YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                     withContext(Dispatchers.Main) {
                         binding.tvStatus.text = "Progresso: $progress%"
                     }
                }

                // Pega o arquivo que foi gerado
                val arquivoGerado = pastaTemp.listFiles()?.firstOrNull()

                if (arquivoGerado != null) {
                    val uriFinal = salvarNaGaleria(arquivoGerado)
                    withContext(Dispatchers.Main) {
                        if (uriFinal != null) {
                            lastDownloadedUri = uriFinal
                            binding.tvStatus.text = "Sucesso! Salvo em Downloads."
                            binding.btnPlay.isEnabled = true
                            mostrarAlerta("Sucesso", "Áudio salvo na pasta Downloads!")
                        } else {
                            binding.tvStatus.text = "Erro ao salvar arquivo final."
                        }
                    }
                } else {
                    throw Exception("O download terminou mas o arquivo sumiu.")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Falha no download."
                    mostrarAlerta("Erro no Download", "Ocorreu um erro:\n${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun salvarNaGaleria(arquivo: File): Uri? {
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Audio_Extrator_${System.currentTimeMillis()}.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        return try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            if (uri != null) {
                contentResolver.openOutputStream(uri).use { output ->
                    FileInputStream(arquivo).use { input ->
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

    private fun mostrarAlerta(titulo: String, mensagem: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensagem)
            .setPositiveButton("OK", null)
            .show()
    }
}


