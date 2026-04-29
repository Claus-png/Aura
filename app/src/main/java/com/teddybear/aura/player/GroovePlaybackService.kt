package com.teddybear.aura.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.Callback
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.teddybear.aura.MainActivity
import com.teddybear.aura.data.AppPreferences
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GroovePlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var eqManager: EqualizerManager
    private lateinit var prefs: AppPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val COMMAND_SET_EQ_STATE = "aura.eq.SET_STATE"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        eqManager = EqualizerManager()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)  // pause on headphone unplug
            .setWakeMode(C.WAKE_MODE_LOCAL)      // keep CPU awake during playback
            .build()
        eqManager.attach(player.audioSessionId)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int,
            ) {
                eqManager.attach(audioSessionId)
                eqManager.restoreState(eqManager.state.value)
            }
        })
        serviceScope.launch {
            eqManager.restoreState(prefs.eqState.first())
        }

        // Tap notification → open app
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: ControllerInfo,
                ): ConnectionResult {
                    val base = super.onConnect(session, controller)
                    return ConnectionResult.accept(
                        base.availableSessionCommands.buildUpon()
                            .add(SessionCommand(COMMAND_SET_EQ_STATE, Bundle.EMPTY))
                            .build(),
                        base.availablePlayerCommands,
                    )
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    return if (customCommand.customAction == COMMAND_SET_EQ_STATE) {
                        val state = EqState(
                            enabled = args.getBoolean("enabled", false),
                            bands = args.getIntArray("bands")?.toList() ?: List(5) { 0 },
                            presetIndex = args.getInt("presetIndex", 0),
                            bassBoost = args.getInt("bassBoost", 0),
                            virtualizer = args.getInt("virtualizer", 0),
                        )
                        eqManager.restoreState(state)
                        serviceScope.launch { prefs.saveEqState(state) }
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        super.onCustomCommand(session, controller, customCommand, args)
                    }
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        eqManager.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
