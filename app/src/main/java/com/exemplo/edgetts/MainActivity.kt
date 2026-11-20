package com.exemplo.edgetts

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvStatus.text = "Status: inicializando GlowTTS..."

        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                attachProgressListener()
                binding.tvStatus.text = "Status: GlowTTS pronto para teste"
            } else {
                ttsReady = false
                binding.tvStatus.text = "Status: falha ao inicializar TTS (código $status)"
            }
        }, packageName)

        binding.btnSpeak.setOnClickListener {
            val text = binding.etSampleText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                binding.tvStatus.text = "Status: digite um texto antes de testar"
                return@setOnClickListener
            }
            if (!ttsReady || tts == null) {
                binding.tvStatus.text = "Status: TTS ainda não está pronto"
                return@setOnClickListener
            }
            binding.tvStatus.text = "Status: sintetizando e reproduzindo..."
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "glowtts_test_utterance")
            if (result == TextToSpeech.ERROR) {
                binding.tvStatus.text = "Status: erro ao enfileirar fala no sistema"
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
                binding.tvStatus.text = "Status: abra Configurações > Acessibilidade > Saída de texto para fala"
            }
        }
    }

    private fun attachProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    binding.tvStatus.text = "Status: falando..."
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    binding.tvStatus.text = "Status: concluído!"
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    binding.tvStatus.text = "Status: erro ao sintetizar voz (verifique internet ou voz)"
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread {
                    binding.tvStatus.text = "Status: erro na síntese (código $errorCode)"
                }
            }
        })
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
