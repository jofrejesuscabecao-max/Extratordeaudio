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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastDownloadedUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configura o WebView Espião (Prepara o terreno)
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
                Toast.makeText(this, "Sistema iniciando... tente novamente em instantes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // PASSO 1: Acessar via navegador primeiro para pegar permissão (Cookies)
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
        // Configura para parecer um Android moderno
        webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        
        // Garante que cookies sejam aceitos
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun autenticarViaNavegador(url: String) {
        // Reseta UI
        binding.progressBar.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.tvStatus.text = "Autenticando..."
        
        val webView = binding.webViewEspiao
        
        // Define o que fazer quando a página carregar
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, urlCarregada: String?) {
                super.onPageFinished(view, urlCarregada)
                
                // A página carregou! Vamos roubar os cookies.
                val cookies = CookieManager.getInstance().getCookie(urlCarregada)
                val userAgent = view?.settings?.userAgentString ?: "Mozilla/5.0"
                
                Log.d("Extrator", "Cookies capturados: $cookies")
                
                // Agora iniciamos o download real com as credenciais
                iniciarDownloadReal(url, cookies, userAgent)
            }
        }
        
        // Carrega a URL no navegador oculto
        webView.loadUrl(url)
    }

    private fun iniciarDownloadReal(url: String, cookies: String?, userAgent: String) {
        binding.tvStatus.text = "Iniciando download..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Permissões
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
                
                // Passa os Cookies capturados pelo WebView (A CHAVE MESTRA)
                if (cookies != null) {
                    request.addHeader("Cookie", cookies)
                }
                request.addOption("--user-agent", userAgent)

                // Configurações FFmpeg
                val ffmpegLib = File(applicationContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
                if (ffmpegLib.exists()) request.addOption("--ffmpeg-location", ffmpegLib.absolutePath)
                else {
                    val f = File(applicationContext.filesDir, "ffmpeg")
                    if (f.exists()) request.addOption("--ffmpeg-location", f.absolutePath)
                }

                // Cliente 'mweb' (Mobile Web) - Combina com o UserAgent do WebView
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
                            // Sucesso!
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

    // Lógica do Player (Tocar/Pausar)
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


