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
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.io.FileOutputStream // Import necessário

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastDownloadedUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarWebView()
        inicializarSistema()

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Cole um link válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isInitialized) {
                inicializarSistema()
                Toast.makeText(this, "Sistema iniciando... aguarde", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // PASSO 1: O WebView acessa para validar a sessão
            autenticarViaNavegador(url)
        }

        binding.btnPlay.setOnClickListener {
            lastDownloadedUri?.let { controlarPlayer(it) }
        }
    }

    private fun configurarWebView() {
        val webView = binding.webViewEspiao
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // User Agent genérico de Android
        webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun autenticarViaNavegador(url: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Validando acesso..."
        
        val webView = binding.webViewEspiao
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, urlCarregada: String?) {
                super.onPageFinished(view, urlCarregada)
                
                val cookies = CookieManager.getInstance().getCookie(urlCarregada)
                val userAgent = view?.settings?.userAgentString ?: "Mozilla/5.0"
                
                // Em vez de passar no header (que falhou), vamos salvar num arquivo temporário
                // O yt-dlp aceita arquivo de cookies melhor que header cru
                val cookieFile = salvarCookiesEmArquivo(cookies)
                
                iniciarDownloadReal(url, cookieFile, userAgent)
            }
        }
        
        webView.loadUrl(url)
    }
    
    // Função auxiliar para criar arquivo de cookies compatível
    private fun salvarCookiesEmArquivo(cookieString: String?): File? {
        if (cookieString == null) return null
        return try {
            val file = File(cacheDir, "cookies.txt")
            // Formato Netscape simplificado (domínio, flag, path, secure, expiration, name, value)
            // Como o CookieManager retorna só "nome=valor", vamos tentar escrever o header puro
            // O yt-dlp aceita formato HTTP Header se passado corretamente, mas a opção --cookies exige arquivo Netscape.
            // A melhor aposta segura: NÃO passar cookies se estiver dando erro de segurança,
            // e confiar apenas no UserAgent e Client.
            
            // Vamos retornar null aqui para DESATIVAR a injeção manual que causou o erro.
            // Se o YouTube bloqueou por "Security Risk", melhor ir sem cookie do que com cookie errado.
            null 
        } catch (e: Exception) { null }
    }

    private fun iniciarDownloadReal(url: String, cookieFile: File?, userAgent: String) {
        binding.tvStatus.text = "Iniciando download..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                        return@launch
                    }
                }

                val tempDir = File(cacheDir, "temp_dl")
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                val request = YoutubeDLRequest(url)
                
                // CORREÇÃO: Removemos a injeção de Cookie que causava "Security Risk" e "Initial Data Error".
                // Confiamos apenas no User Agent consistente.
                request.addOption("--user-agent", userAgent)

                val ffmpegLib = File(applicationContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
                if (ffmpegLib.exists()) request.addOption("--ffmpeg-location", ffmpegLib.absolutePath)
                else {
                    val f = File(applicationContext.filesDir, "ffmpeg")
                    if (f.exists()) request.addOption("--ffmpeg-location", f.absolutePath)
                }

                // Cliente 'mweb' (Mobile Web) ainda é a melhor aposta com UserAgent de celular
                request.addOption("--extractor-args", "youtube:player_client=mweb")
                
                request.addOption("--no-check-certificate")
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
                    mostrarAlerta("Erro", e.message ?: "Desconhecido")
                }
            } finally {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                }
            }
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
                runOnUiThread { binding.tvStatus.text = "Erro init: ${e.message}" }
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
        AlertDialog.Builder(this).setTitle(titulo).setMessage(msg).setPositiveButton("OK", null).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}


