package com.exemplo.edgetts

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
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
        private const val KEY_VOICE_LIST = "selected_voices_list"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val ALL_LANGUAGES_OPTION = "Todos os idiomas"
    }

    private lateinit var binding: ActivitySettingsBinding
    private val client = EdgeTtsClient()
    private val executor = Executors.newSingleThreadExecutor()

    private var allVoices: List<EdgeVoice> = EdgeTtsClient.DEFAULT_FALLBACK_VOICES
    private var languageOptions: List<String> = listOf(ALL_LANGUAGES_OPTION)
    private var selectedLanguage: String = ALL_LANGUAGES_OPTION
    private var filteredVoices: List<EdgeVoice> = EdgeTtsClient.DEFAULT_FALLBACK_VOICES
    private var savedVoice: String = DEFAULT_VOICE
    private val activeVoiceQueue = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        savedVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_VOICE }
        val savedRate = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 50)
        val savedPitch = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)

        val rawList = prefs.getString(KEY_VOICE_LIST, savedVoice).orEmpty()
        val parsedList = rawList.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        activeVoiceQueue.clear()
        if (parsedList.isNotEmpty()) {
            activeVoiceQueue.addAll(parsedList)
        } else {
            activeVoiceQueue.add(savedVoice)
        }
        updateActiveVoicesLabel()

        rebuildLanguagesAndVoices(allVoices, savedVoice)
        binding.tvSettingsStatus.text = "Carregando catálogo completo de vozes..."

        binding.sbRate.progress = savedRate + 50
        binding.tvRateLabel.text = "Velocidade extra: ${formatSignedPercent(savedRate)}"

        binding.sbPitch.progress = savedPitch + 50
        binding.tvPitchLabel.text = "Tom extra: ${formatSignedHz(savedPitch)}"

        binding.spLanguages.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedLanguage = languageOptions.getOrElse(position) { ALL_LANGUAGES_OPTION }
                applyFilters(binding.etVoiceSearch.text?.toString().orEmpty(), savedVoice)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.etVoiceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters(s?.toString().orEmpty(), savedVoice)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnAddVoiceToList.setOnClickListener {
            val currentVoice = filteredVoices.getOrNull(binding.spVoices.selectedItemPosition)?.shortName
                ?: savedVoice
            if (currentVoice.isNotBlank() && !activeVoiceQueue.contains(currentVoice)) {
                activeVoiceQueue.add(currentVoice)
            }
            savedVoice = activeVoiceQueue.firstOrNull() ?: currentVoice
            updateActiveVoicesLabel()
            binding.tvSettingsStatus.text = "Adicionada: $currentVoice (${activeVoiceQueue.size} voz(es) na fila)"
        }

        binding.btnClearVoiceList.setOnClickListener {
            val currentVoice = filteredVoices.getOrNull(binding.spVoices.selectedItemPosition)?.shortName
                ?: savedVoice
            activeVoiceQueue.clear()
            activeVoiceQueue.add(currentVoice)
            savedVoice = currentVoice
            updateActiveVoicesLabel()
            binding.tvSettingsStatus.text = "Modo de voz única: $currentVoice"
        }

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
            val chosenVoice = selectedVoiceObj?.shortName ?: savedVoice
            savedVoice = chosenVoice

            if (activeVoiceQueue.isEmpty()) {
                activeVoiceQueue.add(chosenVoice)
            } else if (!activeVoiceQueue.contains(chosenVoice)) {
                activeVoiceQueue[0] = chosenVoice
            }

            val ratePercent = binding.sbRate.progress - 50
            val pitchHz = binding.sbPitch.progress - 50
            val serializedQueue = activeVoiceQueue.joinToString("|")

            prefs.edit()
                .putString(KEY_VOICE, savedVoice)
                .putString(KEY_VOICE_LIST, serializedQueue)
                .putInt(KEY_RATE_PERCENT, ratePercent)
                .putInt(KEY_PITCH_HZ, pitchHz)
                .apply()

            updateActiveVoicesLabel()
            binding.tvSettingsStatus.text = "Salvo! Vozes: $serializedQueue"
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
                    rebuildLanguagesAndVoices(allVoices, savedVoice)
                    binding.tvSettingsStatus.text = "${remoteVoices.size} vozes carregadas! Voz atual: $savedVoice"
                    binding.btnReloadVoices.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    rebuildLanguagesAndVoices(allVoices, savedVoice)
                    binding.tvSettingsStatus.text = "Usando lista local (${allVoices.size} vozes). Erro remoto: ${e.message}"
                    binding.btnReloadVoices.isEnabled = true
                }
            }
        }
    }

    private fun rebuildLanguagesAndVoices(voices: List<EdgeVoice>, targetShortName: String) {
        val distinctLocales = voices
            .map { it.locale.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(
                compareBy<String> { !it.startsWith("pt", ignoreCase = true) }
                    .thenBy { it }
            )

        languageOptions = listOf(ALL_LANGUAGES_OPTION) + distinctLocales

        val targetVoiceLocale = voices.firstOrNull {
            it.shortName.equals(targetShortName, ignoreCase = true)
        }?.locale

        if (selectedLanguage == ALL_LANGUAGES_OPTION && !targetVoiceLocale.isNullOrBlank()) {
            selectedLanguage = targetVoiceLocale
        }

        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageOptions)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spLanguages.adapter = langAdapter

        val langIdx = languageOptions.indexOfFirst { it.equals(selectedLanguage, ignoreCase = true) }
        if (langIdx >= 0) {
            binding.spLanguages.setSelection(langIdx)
        } else {
            selectedLanguage = ALL_LANGUAGES_OPTION
            binding.spLanguages.setSelection(0)
        }

        applyFilters(binding.etVoiceSearch.text?.toString().orEmpty(), targetShortName)
    }

    private fun applyFilters(query: String, targetShortName: String) {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        filteredVoices = allVoices.filter { voice ->
            val matchesLang = selectedLanguage == ALL_LANGUAGES_OPTION ||
                voice.locale.equals(selectedLanguage, ignoreCase = true)
            val matchesSearch = trimmed.isEmpty() ||
                voice.displayLabel().lowercase(Locale.ROOT).contains(trimmed)
            matchesLang && matchesSearch
        }

        val listToShow = if (filteredVoices.isEmpty()) allVoices else filteredVoices
        filteredVoices = listToShow

        val labels = listToShow.map { it.displayLabel() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spVoices.adapter = adapter

        val index = listToShow.indexOfFirst { it.shortName.equals(targetShortName, ignoreCase = true) }
        if (index >= 0) {
            binding.spVoices.setSelection(index)
        }
    }

    private fun updateActiveVoicesLabel() {
        val display = if (activeVoiceQueue.isEmpty()) savedVoice else activeVoiceQueue.joinToString(" → ")
        binding.tvActiveVoicesList.text = "Vozes em rotação: $display"
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
