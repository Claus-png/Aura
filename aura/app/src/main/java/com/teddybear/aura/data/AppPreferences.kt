package com.teddybear.aura.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("groove_prefs")

class AppPreferences(private val ctx: Context) {

    companion object {
        // ── Proxy ─────────────────────────────────────────────────────────────
        val PROXY_ENABLED   = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE      = stringPreferencesKey("proxy_type")      // SOCKS5 | HTTP | VLESS | MTPROTO
        val PROXY_HOST      = stringPreferencesKey("proxy_host")
        val PROXY_PORT      = intPreferencesKey("proxy_port")
        val PROXY_USER      = stringPreferencesKey("proxy_user")
        val PROXY_HAS_PASS  = booleanPreferencesKey("proxy_has_pass")

        // ── VLESS / MTProto parsed fields ─────────────────────────────────────
        val VLESS_UUID      = stringPreferencesKey("vless_uuid")
        val VLESS_HOST      = stringPreferencesKey("vless_host")
        val VLESS_PORT      = intPreferencesKey("vless_port")
        val VLESS_SECURITY  = stringPreferencesKey("vless_security")
        val VLESS_PBK       = stringPreferencesKey("vless_pbk")
        val VLESS_FP        = stringPreferencesKey("vless_fp")
        val VLESS_SID       = stringPreferencesKey("vless_sid")
        val VLESS_FLOW      = stringPreferencesKey("vless_flow")
        val MTPROTO_SERVER  = stringPreferencesKey("mtproto_server")
        val MTPROTO_PORT    = intPreferencesKey("mtproto_port")
        val MTPROTO_SECRET  = stringPreferencesKey("mtproto_secret")

        // ── Region detection cache ─────────────────────────────────────────────
        val REGION_CODE       = stringPreferencesKey("region_code")
        val REGION_CHECKED_AT = longPreferencesKey("region_checked_at")

        // ── Audio quality ──────────────────────────────────────────────────────
        val AUDIO_BITRATE    = stringPreferencesKey("audio_bitrate")
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")

        // ── Playback modes (persist across restarts) ──────────────────────────
        val REPEAT_MODE      = intPreferencesKey("repeat_mode")       // 0=off,1=all,2=one
        val SHUFFLE_ON       = booleanPreferencesKey("shuffle_on")

        // ── Stats tracking ─────────────────────────────────────────────────────
        val TRACK_STATS      = booleanPreferencesKey("track_stats")   // default true

        // ── Player state persistence ───────────────────────────────────────────
        val LAST_TRACK_ID    = intPreferencesKey("last_track_id")       // -1 = none
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")   // playback position
        val LAST_QUEUE_IDS   = stringPreferencesKey("last_queue_ids")   // comma-sep track IDs

        // ── Dynamic playlist custom names ──────────────────────────────────────
        val DYN_NAME_ALL     = stringPreferencesKey("dyn_name_all")
        val DYN_NAME_FREQUENT= stringPreferencesKey("dyn_name_frequent")
        val DYN_NAME_RARE    = stringPreferencesKey("dyn_name_rare")

        // ── UI state persistence ───────────────────────────────────────────────
        val PROXY_BANNER_DISMISSED = booleanPreferencesKey("proxy_banner_dismissed")

        // ── Equalizer ────────────────────────────────────────────────────────
        val EQ_ENABLED       = booleanPreferencesKey("eq_enabled")
        val EQ_BANDS         = stringPreferencesKey("eq_bands")
        val EQ_PRESET_INDEX  = intPreferencesKey("eq_preset_index")
        val EQ_BASS          = intPreferencesKey("eq_bass")
        val EQ_VIRT          = intPreferencesKey("eq_virt")
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    val proxyEnabled:  Flow<Boolean> = ctx.dataStore.data.map { it[PROXY_ENABLED]  ?: false }
    val proxyType:     Flow<String>  = ctx.dataStore.data.map { it[PROXY_TYPE]     ?: "SOCKS5" }
    val proxyHost:     Flow<String>  = ctx.dataStore.data.map { it[PROXY_HOST]     ?: "" }
    val proxyPort:     Flow<Int>     = ctx.dataStore.data.map { it[PROXY_PORT]     ?: 1080 }
    val proxyUser:     Flow<String>  = ctx.dataStore.data.map { it[PROXY_USER]     ?: "" }
    val proxyHasPass:  Flow<Boolean> = ctx.dataStore.data.map { it[PROXY_HAS_PASS] ?: false }

    val vlessUuid:     Flow<String>  = ctx.dataStore.data.map { it[VLESS_UUID]     ?: "" }
    val vlessHost:     Flow<String>  = ctx.dataStore.data.map { it[VLESS_HOST]     ?: "" }
    val vlessPort:     Flow<Int>     = ctx.dataStore.data.map { it[VLESS_PORT]     ?: 443 }
    val vlessSecurity: Flow<String>  = ctx.dataStore.data.map { it[VLESS_SECURITY] ?: "" }
    val vlessPbk:      Flow<String>  = ctx.dataStore.data.map { it[VLESS_PBK]      ?: "" }
    val mtprotoServer: Flow<String>  = ctx.dataStore.data.map { it[MTPROTO_SERVER] ?: "" }
    val mtprotoPort:   Flow<Int>     = ctx.dataStore.data.map { it[MTPROTO_PORT]   ?: 443 }
    val mtprotoSecret: Flow<String>  = ctx.dataStore.data.map { it[MTPROTO_SECRET] ?: "" }

    val regionCode:      Flow<String> = ctx.dataStore.data.map { it[REGION_CODE]       ?: "" }
    val regionCheckedAt: Flow<Long>   = ctx.dataStore.data.map { it[REGION_CHECKED_AT] ?: 0L }

    val audioBitrate:    Flow<String>  = ctx.dataStore.data.map { it[AUDIO_BITRATE]     ?: "auto" }
    val normalizeVolume: Flow<Boolean> = ctx.dataStore.data.map { it[NORMALIZE_VOLUME]  ?: true }
    val repeatMode:      Flow<Int>     = ctx.dataStore.data.map { it[REPEAT_MODE]       ?: 0 }
    val shuffleOn:       Flow<Boolean> = ctx.dataStore.data.map { it[SHUFFLE_ON]        ?: false }
    val trackStats:      Flow<Boolean> = ctx.dataStore.data.map { it[TRACK_STATS]       ?: true }
    val lastTrackId:     Flow<Int>     = ctx.dataStore.data.map { it[LAST_TRACK_ID]     ?: -1 }
    val lastPositionMs:  Flow<Long>    = ctx.dataStore.data.map { it[LAST_POSITION_MS]  ?: 0L }
    val lastQueueIds:    Flow<String>  = ctx.dataStore.data.map { it[LAST_QUEUE_IDS]    ?: "" }
    val dynNameAll:      Flow<String>  = ctx.dataStore.data.map { it[DYN_NAME_ALL]      ?: "Библиотека" }
    val dynNameFrequent: Flow<String>  = ctx.dataStore.data.map { it[DYN_NAME_FREQUENT] ?: "Часто слушаю" }
    val dynNameRare:     Flow<String>  = ctx.dataStore.data.map { it[DYN_NAME_RARE]     ?: "Редкие треки" }
    val eqState: Flow<com.teddybear.aura.player.EqState> = ctx.dataStore.data.map { prefs ->
        com.teddybear.aura.player.EqState(
            enabled = prefs[EQ_ENABLED] ?: false,
            bands = prefs[EQ_BANDS]
                ?.split(",")
                ?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 5 }
                ?: List(5) { 0 },
            presetIndex = prefs[EQ_PRESET_INDEX] ?: 0,
            bassBoost = prefs[EQ_BASS] ?: 0,
            virtualizer = prefs[EQ_VIRT] ?: 0,
        )
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    suspend fun saveProxySettings(
        enabled: Boolean, type: String, host: String,
        port: Int, user: String, hasPass: Boolean,
    ) {
        ctx.dataStore.edit { p ->
            p[PROXY_ENABLED]  = enabled
            p[PROXY_TYPE]     = type
            p[PROXY_HOST]     = host
            p[PROXY_PORT]     = port
            p[PROXY_USER]     = user
            p[PROXY_HAS_PASS] = hasPass
        }
    }

    suspend fun saveVlessConfig(
        uuid: String, host: String, port: Int,
        security: String, pbk: String, fp: String, sid: String, flow: String,
    ) {
        ctx.dataStore.edit { p ->
            p[VLESS_UUID]     = uuid
            p[VLESS_HOST]     = host
            p[VLESS_PORT]     = port
            p[VLESS_SECURITY] = security
            p[VLESS_PBK]      = pbk
            p[VLESS_FP]       = fp
            p[VLESS_SID]      = sid
            p[VLESS_FLOW]     = flow
            // When VLESS is active, OkHttp points to local SOCKS5 tunnel
            p[PROXY_TYPE]     = "VLESS"
            p[PROXY_HOST]     = "127.0.0.1"
            p[PROXY_PORT]     = 10808
            p[PROXY_ENABLED]  = true
        }
    }

    suspend fun saveMtprotoConfig(server: String, port: Int, secret: String) {
        ctx.dataStore.edit { p ->
            p[MTPROTO_SERVER] = server
            p[MTPROTO_PORT]   = port
            p[MTPROTO_SECRET] = secret
            p[PROXY_TYPE]     = "MTPROTO"
            p[PROXY_HOST]     = "127.0.0.1"
            p[PROXY_PORT]     = 10808
            p[PROXY_ENABLED]  = true
        }
    }

    suspend fun setProxyEnabled(enabled: Boolean) {
        ctx.dataStore.edit { it[PROXY_ENABLED] = enabled }
    }

    suspend fun cacheRegion(code: String) {
        ctx.dataStore.edit { p ->
            p[REGION_CODE]       = code
            p[REGION_CHECKED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setAudioBitrate(bitrate: String) { ctx.dataStore.edit { it[AUDIO_BITRATE] = bitrate } }
    suspend fun setRepeatMode(mode: Int)          { ctx.dataStore.edit { it[REPEAT_MODE]  = mode  } }
    suspend fun setShuffleOn(on: Boolean)          { ctx.dataStore.edit { it[SHUFFLE_ON]  = on    } }
    suspend fun setTrackStats(on: Boolean)         { ctx.dataStore.edit { it[TRACK_STATS] = on    } }
    val proxyBannerDismissed: Flow<Boolean> = ctx.dataStore.data.map { it[PROXY_BANNER_DISMISSED] ?: false }
    suspend fun saveProxyBannerDismissed(v: Boolean) { ctx.dataStore.edit { it[PROXY_BANNER_DISMISSED] = v } }

    suspend fun savePlayerState(trackId: Int, positionMs: Long, queueIds: List<Int>) {
        ctx.dataStore.edit { p ->
            p[LAST_TRACK_ID]    = trackId
            p[LAST_POSITION_MS] = positionMs
            p[LAST_QUEUE_IDS]   = queueIds.joinToString(",")
        }
    }
    suspend fun clearPlayerState() {
        ctx.dataStore.edit { p ->
            p[LAST_TRACK_ID]    = -1
            p[LAST_POSITION_MS] = 0L
            p[LAST_QUEUE_IDS]   = ""
        }
    }
    suspend fun setDynNameAll(n: String)           { ctx.dataStore.edit { it[DYN_NAME_ALL]      = n } }
    suspend fun setDynNameFrequent(n: String)      { ctx.dataStore.edit { it[DYN_NAME_FREQUENT] = n } }
    suspend fun setDynNameRare(n: String)          { ctx.dataStore.edit { it[DYN_NAME_RARE]     = n } }

    suspend fun setNormalizeVolume(on: Boolean) {
        ctx.dataStore.edit { it[NORMALIZE_VOLUME] = on }
    }

    suspend fun saveEqState(state: com.teddybear.aura.player.EqState) {
        ctx.dataStore.edit { prefs ->
            prefs[EQ_ENABLED] = state.enabled
            prefs[EQ_BANDS] = state.bands.joinToString(",")
            prefs[EQ_PRESET_INDEX] = state.presetIndex
            prefs[EQ_BASS] = state.bassBoost
            prefs[EQ_VIRT] = state.virtualizer
        }
    }
}
