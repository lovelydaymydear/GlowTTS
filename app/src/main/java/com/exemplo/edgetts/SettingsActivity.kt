package com.exemplo.edgetts

import android.content.Context
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        val savedRate = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 50)
        val savedPitch = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)

        when (savedVoice) {
            "pt-BR-AntonioNeural" -> binding.rbPtBrAntonio.isChecked = true
            "pt-PT-RaquelNeural" -> binding.rbPtPtRaquel.isChecked = true
            "en-US-JennyNeural" -> binding.rbEnUsJenny.isChecked = true
            "es-ES-ElviraNeural" -> binding.rbEsEsElvira.isChecked = true
            else -> binding.rbPtBrFrancisca.isChecked = true
        }

        binding.sbRate.progress = savedRate + 50
        binding.tvRateLabel.text = "Velocidade extra: ${formatSignedPercent(savedRate)}"

        binding.sbPitch.progress = savedPitch + 50
        binding.tvPitchLabel.text = "Tom extra: ${formatSignedHz(savedPitch)}"

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
            val selectedVoice = when (binding.rgVoices.checkedRadioButtonId) {
                binding.rbPtBrAntonio.id -> "pt-BR-AntonioNeural"
                binding.rbPtPtRaquel.id -> "pt-PT-RaquelNeural"
                binding.rbEnUsJenny.id -> "en-US-JennyNeural"
                binding.rbEsEsElvira.id -> "es-ES-ElviraNeural"
                else -> "pt-BR-FranciscaNeural"
            }

            val ratePercent = binding.sbRate.progress - 50
            val pitchHz = binding.sbPitch.progress - 50

            prefs.edit()
                .putString(KEY_VOICE, selectedVoice)
                .putInt(KEY_RATE_PERCENT, ratePercent)
                .putInt(KEY_PITCH_HZ, pitchHz)
                .apply()

            binding.tvSettingsStatus.text = "Salvo! Voz: $selectedVoice"
        }
    }

    private fun formatSignedPercent(value: Int): String {
        return if (value >= 0) "+$value%" else "$value%"
    }

    private fun formatSignedHz(value: Int): String {
        return if (value >= 0) "+${value}Hz" else "${value}Hz"
    }
}
