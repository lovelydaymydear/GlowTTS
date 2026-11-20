package com.exemplo.edgetts

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivitySettingsBinding
import com.exemplo.edgetts.protocol.EdgeTtsClient
import com.exemplo.edgetts.protocol.EdgeVoice
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
    }

    private lateinit var binding: ActivitySettingsBinding
    private val client = EdgeTtsClient()
    private val executor = Executors.newSingleThreadExecutor()

    private var allVoices: List<EdgeVoice> = EdgeTtsClient.DEFAULT_FALLBACK_VOICES
    private var filteredVoices: List<EdgeVoice> = EdgeTtsClient.DEFAULT_FALLBACK_VOICES
    private var savedVoice: String = DEFAULT_VOICE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        savedVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        val savedRate = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 50)
        val savedPitch = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)

        applyVoicesToSpinner(allVoices, savedVoice)
        binding.tvSettingsStatus.text = "Carregando catálogo completo de vozes..."

        binding.sbRate.progress = savedRate + 50
        binding.tvRateLabel.text = "Velocidade extra: ${formatSignedPercent(savedRate)}"

        binding.sbPitch.progress = savedPitch + 50
        binding.tvPitchLabel.text = "Tom extra: ${formatSignedHz(savedPitch)}"

        binding.etVoiceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterVoices(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnReloadVoices.setOnClickListener {
            loadRemoteVoices()
        }

        binding.sbRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = progress - 50
                binding.tvRateLabel.text = "Velocidade extra: ${formatSignedPercent(rate)}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progress - 50
                binding.tvPitchLabel.text = "Tom extra: ${formatSignedHz(pitch)}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnSaveSettings.setOnClickListener {
            val selectedVoiceObj = filteredVoices.getOrNull(binding.spVoices.selectedItemPosition)
            val selectedVoice = selectedVoiceObj?.shortName ?: savedVoice
            savedVoice = selectedVoice

            val ratePercent = binding.sbRate.progress - 50
            val pitchHz = binding.sbPitch.progress - 50

            prefs.edit()
                .putString(KEY_VOICE, selectedVoice)
                .putInt(KEY_RATE_PERCENT, ratePercent)
                .putInt(KEY_PITCH_HZ, pitchHz)
                .apply()

            binding.tvSettingsStatus.text = "Salvo! Voz ativa: $selectedVoice"
        }

        loadRemoteVoices()
    }

    private fun loadRemoteVoices() {
        binding.btnReloadVoices.isEnabled = false
        binding.tvSettingsStatus.text = "Buscando todas as vozes no servidor..."
        executor.execute {
            try {
                val remoteVoices = client.fetchAllVoices()
                runOnUiThread {
                    allVoices = remoteVoices
                    filterVoices(binding.etVoiceSearch.text?.toString().orEmpty())
                    binding.tvSettingsStatus.text = "${remoteVoices.size} vozes carregadas! Voz atual: $savedVoice"
                    binding.btnReloadVoices.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvSettingsStatus.text = "Usando lista local (${allVoices.size} vozes). Erro remoto: ${e.message}"
                    binding.btnReloadVoices.isEnabled = true
                }
            }
        }
    }

    private fun filterVoices(query: String) {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        filteredVoices = if (trimmed.isEmpty()) {
            allVoices
        } else {
            allVoices.filter { voice ->
                voice.displayLabel().lowercase(Locale.ROOT).contains(trimmed)
            }
        }
        applyVoicesToSpinner(filteredVoices, savedVoice)
    }

    private fun applyVoicesToSpinner(list: List<EdgeVoice>, targetShortName: String) {
        val labels = list.map { it.displayLabel() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spVoices.adapter = adapter

        val index = list.indexOfFirst { it.shortName.equals(targetShortName, ignoreCase = true) }
        if (index >= 0) {
            binding.spVoices.setSelection(index)
        }
    }

    private fun formatSignedPercent(value: Int): String {
        return if (value >= 0) "+$value%" else "$value%"
    }

    private fun formatSignedHz(value: Int): String {
        return if (value >= 0) "+${value}Hz" else "${value}Hz"
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
