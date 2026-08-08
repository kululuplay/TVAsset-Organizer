package com.iptv.player.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Platform playback integration shared by the live and VOD screens.
 *
 * The actual decoder remains owned by PlayerController/VOD. This class only owns
 * media-button routing, audio focus and the noisy-output receiver, so VLC and
 * Media3 behave consistently when another app starts speaking or headphones are
 * disconnected. No background playback service is created.
 */
class TvPlaybackSession(
    context: Context,
    tag: String,
    private val controls: Controls,
) {

    data class Controls(
        val onPlay: () -> Unit,
        val onPause: () -> Unit,
        val onToggle: () -> Unit,
        val onStop: () -> Unit = onPause,
        val onNext: (() -> Unit)? = null,
        val onPrevious: (() -> Unit)? = null,
        val onSeekBy: ((Long) -> Unit)? = null,
    )

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSession = MediaSession(appContext, tag)
    private var registeredNoisyReceiver = false
    private var resumeAfterFocusGain = false
    private var playing = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (AudioFocusPolicy.actionFor(change, playing)) {
            AudioFocusPolicy.Action.PAUSE_AND_RESUME_ON_GAIN -> {
                resumeAfterFocusGain = true
                controls.onPause()
                setPlaying(false)
            }
            AudioFocusPolicy.Action.PAUSE -> {
                resumeAfterFocusGain = false
                controls.onPause()
                setPlaying(false)
            }
            AudioFocusPolicy.Action.RESUME -> {
                if (resumeAfterFocusGain) {
                    resumeAfterFocusGain = false
                    controls.onPlay()
                    setPlaying(true)
                }
            }
            AudioFocusPolicy.Action.NONE -> Unit
        }
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    // Keep the API-26-only class behind an Object reference. Some API 21 vendor
    // verifiers eagerly resolve field types even when the call site is guarded.
    private val focusRequest: Any? = if (Build.VERSION.SDK_INT >= 26) {
        Api26AudioFocus.create(audioAttributes, focusListener)
    } else {
        null
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) {
                resumeAfterFocusGain = false
                controls.onPause()
                setPlaying(false)
            }
        }
    }

    init {
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = controls.onPlay()
            override fun onPause() = controls.onPause()
            override fun onStop() = controls.onStop()
            override fun onSkipToNext() = controls.onNext?.invoke() ?: Unit
            override fun onSkipToPrevious() = controls.onPrevious?.invoke() ?: Unit
            override fun onFastForward() = controls.onSeekBy?.invoke(10_000L) ?: Unit
            override fun onRewind() = controls.onSeekBy?.invoke(-10_000L) ?: Unit
            override fun onSeekTo(pos: Long) {
                // This wrapper intentionally exposes relative seeking only. The
                // visual VOD seekbar remains the authoritative absolute seeker.
            }

            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            controls.onToggle()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        // Activities opt in from onStart and opt out at the beginning of onStop.
        mediaSession.isActive = false
        publishState(PlaybackState.STATE_PAUSED)
    }

    fun requestAudioFocus(): Boolean {
        registerNoisyReceiver()
        val result = if (Build.VERSION.SDK_INT >= 26) {
            Api26AudioFocus.request(audioManager, requireNotNull(focusRequest))
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) unregisterNoisyReceiver()
        return granted
    }

    fun abandonAudioFocus() {
        resumeAfterFocusGain = false
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { Api26AudioFocus.abandon(audioManager, it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        unregisterNoisyReceiver()
    }

    fun setPlaying(isPlaying: Boolean, positionMs: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
        playing = isPlaying
        publishState(
            if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            positionMs,
        )
    }

    /**
     * Media buttons must never restart a decoder after its Activity left STARTED.
     * Audio focus is still released by the owner because that also closes its
     * provider socket; this gate only controls platform callback delivery.
     */
    fun setActive(active: Boolean) {
        if (!active) {
            resumeAfterFocusGain = false
            playing = false
            publishState(PlaybackState.STATE_PAUSED)
        }
        mediaSession.isActive = active
    }

    fun release() {
        abandonAudioFocus()
        setActive(false)
        mediaSession.release()
    }

    private fun publishState(
        state: Int,
        positionMs: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
    ) {
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        if (controls.onNext != null) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (controls.onPrevious != null) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (controls.onSeekBy != null) {
            actions = actions or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    positionMs,
                    if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    private fun registerNoisyReceiver() {
        if (registeredNoisyReceiver) return
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registeredNoisyReceiver = true
    }

    private fun unregisterNoisyReceiver() {
        if (!registeredNoisyReceiver) return
        registeredNoisyReceiver = false
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }
}

/** API-26 symbols are isolated so loading [TvPlaybackSession] remains API-21 safe. */
@RequiresApi(26)
private object Api26AudioFocus {
    fun create(
        attributes: AudioAttributes,
        listener: AudioManager.OnAudioFocusChangeListener,
    ): AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener)
        .setWillPauseWhenDucked(true)
        .build()

    fun request(manager: AudioManager, request: Any): Int =
        manager.requestAudioFocus(request as AudioFocusRequest)

    fun abandon(manager: AudioManager, request: Any): Int =
        manager.abandonAudioFocusRequest(request as AudioFocusRequest)
}
