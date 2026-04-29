package com.teddybear.aura.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "GrooveEQ"

data class EqPreset(val name: String, val bands: List<Int>)   // band values in milliBel
data class EqState(
    val enabled: Boolean        = false,
    val bands: List<Int>        = List(5) { 0 },
    val presetIndex: Int        = 0,
    val bassBoost: Int          = 0,      // 0–1000
    val virtualizer: Int        = 0,      // 0–1000
)

/**
 * Manages Android AudioEffect Equalizer, BassBoost and Virtualizer.
 * Must be attached to the ExoPlayer audio session ID after player init.
 */
class EqualizerManager {

    companion object {
        val DEFAULT_BAND_FREQUENCIES = listOf("60Hz", "230Hz", "910Hz", "3k", "14k")
        const val DEFAULT_BAND_MIN = -1500
        const val DEFAULT_BAND_MAX = 1500

        val PRESETS: List<EqPreset> = listOf(
            EqPreset("Flat",       listOf(0, 0, 0, 0, 0)),
            EqPreset("Bass Boost", listOf(600, 400, 0, 0, 0)),
            EqPreset("Треки",      listOf(300, 200, 0, 100, 200)),
            EqPreset("Rock",       listOf(400, 100, -200, 100, 400)),
            EqPreset("Pop",        listOf(-100, 200, 400, 200, -100)),
            EqPreset("Jazz",       listOf(200, 100, -100, 200, 300)),
            EqPreset("Hip-Hop",    listOf(500, 300, 0, -100, 200)),
            EqPreset("Electronic", listOf(400, 300, 0, 200, 400)),
            EqPreset("Classical",  listOf(400, 200, -100, 200, 300)),
            EqPreset("Vocal",      listOf(-200, 0, 300, 300, -100)),
        )
    }

    private var eq: Equalizer?   = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    /**
     * Call after ExoPlayer session is ready.
     * sessionId = player.audioSessionId — must be != 0.
     * Creates AudioEffect objects ONLY if sessionId is valid to prevent
     * attaching to the global mixer (sessionId=0), which causes audio glitches.
     */
    fun attach(sessionId: Int) {
        if (sessionId == 0) {
            Log.w(TAG, "attach() called with sessionId=0 — skipping to avoid global EQ glitch")
            return
        }
        release()
        try {
            eq   = Equalizer(0, sessionId).also { it.enabled = false }
            bass = BassBoost(0, sessionId).also { it.enabled = false }
            virt = Virtualizer(0, sessionId).also { it.enabled = false }
            // Apply current state (enabled=false unless user turns it on)
            if (_state.value.enabled) applyState(_state.value)
            Log.d(TAG, "EQ attached to session $sessionId, bands=${eq?.numberOfBands}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not create Equalizer for session $sessionId: ${e.message}")
            release()
        }
    }

    fun release() {
        try { eq?.release()   } catch (_: Exception) {}
        try { bass?.release() } catch (_: Exception) {}
        try { virt?.release() } catch (_: Exception) {}
        eq   = null
        bass = null
        virt = null
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun setEnabled(on: Boolean) {
        eq?.enabled   = on
        bass?.enabled = on
        virt?.enabled = on
        _state.value  = _state.value.copy(enabled = on)
    }

    /** Set single band value. [bandIndex] 0–4, [value] in milliBel (e.g. -1500..1500). */
    fun setBand(bandIndex: Int, value: Int) {
        val clamped = value.coerceIn(bandMin, bandMax)
        eq?.setBandLevel(bandIndex.toShort(), clamped.toShort())
        val newBands = _state.value.bands.toMutableList().also { it[bandIndex] = clamped }
        _state.value = _state.value.copy(bands = newBands, presetIndex = 0)
    }

    fun applyPreset(index: Int) {
        val preset = PRESETS.getOrNull(index) ?: return
        preset.bands.forEachIndexed { i, v ->
            try { eq?.setBandLevel(i.toShort(), v.toShort()) } catch (_: Exception) {}
        }
        _state.value = _state.value.copy(bands = preset.bands, presetIndex = index)
    }

    fun setBassBoost(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        try { bass?.setStrength(s.toShort()) } catch (_: Exception) {}
        _state.value = _state.value.copy(bassBoost = s)
    }

    fun setVirtualizer(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        try { virt?.setStrength(s.toShort()) } catch (_: Exception) {}
        _state.value = _state.value.copy(virtualizer = s)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    val bandCount: Int get() = eq?.numberOfBands?.toInt() ?: 5
    val bandMin: Int get() = eq?.bandLevelRange?.get(0)?.toInt() ?: DEFAULT_BAND_MIN
    val bandMax: Int get() = eq?.bandLevelRange?.get(1)?.toInt() ?: DEFAULT_BAND_MAX

    /** Band center frequencies in Hz for labels */
    val bandFrequencies: List<String>
        get() = (0 until bandCount).map { i ->
            val hz = (eq?.getCenterFreq(i.toShort()) ?: 0) / 1000
            if (hz > 0) {
                if (hz >= 1000) "${hz / 1000}k" else "${hz}Hz"
            } else {
                DEFAULT_BAND_FREQUENCIES.getOrElse(i) { "Band ${i + 1}" }
            }
        }

    fun restoreState(state: EqState) {
        _state.value = state
        applyState(state)
    }

    private fun applyState(s: EqState) {
        eq?.enabled   = s.enabled
        bass?.enabled = s.enabled
        virt?.enabled = s.enabled
        s.bands.forEachIndexed { i, v ->
            try { eq?.setBandLevel(i.toShort(), v.toShort()) } catch (_: Exception) {}
        }
        try { bass?.setStrength(s.bassBoost.toShort())   } catch (_: Exception) {}
        try { virt?.setStrength(s.virtualizer.toShort()) } catch (_: Exception) {}
    }
}
