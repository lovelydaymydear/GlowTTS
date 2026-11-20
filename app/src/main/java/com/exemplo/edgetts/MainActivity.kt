package com.exemplo.edgetts

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
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

        binding.tvStatus.text = "Status: inicializando mecanismo TTS..."

        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                binding.tvStatus.text = "Status: mecanismo TTS pronto para teste"
            } else {
                ttsReady = false
                binding.tvStatus.text = "Status: falha ao inicializar TTS ($status)"
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
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edge_tts_test_utterance")
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
