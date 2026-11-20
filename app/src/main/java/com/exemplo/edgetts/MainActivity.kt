package com.exemplo.edgetts

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchDogRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvStatus.text = "Status: a ligar ao GlowTTS ($packageName)..."

        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                attachProgressListener()
                val currentEngine = tts?.defaultEngine ?: packageName
                binding.tvStatus.text = "Status: pronto (motor: $currentEngine)"
            } else {
                ttsReady = false
                binding.tvStatus.text = "Status: falha ao iniciar motor (código $status)"
            }
        }, packageName)

        binding.btnSpeak.setOnClickListener {
            val text = binding.etSampleText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                binding.tvStatus.text = "Status: escreva um texto antes de testar"
                return@setOnClickListener
            }
            if (!ttsReady || tts == null) {
                binding.tvStatus.text = "Status: motor ainda não inicializado"
                return@setOnClickListener
            }

            val prefs = getSharedPreferences("edge_tts_prefs", MODE_PRIVATE)
            val voice = prefs.getString("selected_voice", "pt-BR-FranciscaNeural") ?: "pt-BR-FranciscaNeural"
            binding.tvStatus.text = "Status: a sintetizar ($voice)..."

            cancelWatchDog()
            val pendingWatchDog = Runnable {
                if (binding.tvStatus.text.toString().startsWith("Status: a sintetizar")) {
                    binding.tvStatus.text = "Status: sem resposta do serviço (verifique internet ou seleção de motor)"
                }
            }
            watchDogRunnable = pendingWatchDog
            mainHandler.postDelayed(pendingWatchDog, 20_000L)

            val params = Bundle()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "glowtts_test_utterance")
            if (result == TextToSpeech.ERROR) {
                cancelWatchDog()
                binding.tvStatus.text = "Status: erro ao enviar texto para o serviço TTS"
            }
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnOpenSystemTts.setOnClickListener {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (_: Exception) {
                binding.tvStatus.text = "Status: abra Definições > Acessibilidade > Saída de texto para fala"
            }
        }
    }

    private fun attachProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvStatus.text = "Status: a reproduzir áudio..."
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvStatus.text = "Status: reprodução concluída!"
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvStatus.text = "Status: erro na síntese (verifique rede ou voz)"
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvStatus.text = "Status: erro na síntese (código $errorCode)"
                }
            }
        })
    }

    private fun cancelWatchDog() {
        watchDogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchDogRunnable = null
    }

    override fun onDestroy() {
        cancelWatchDog()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
