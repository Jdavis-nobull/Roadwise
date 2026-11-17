package com.roadwise.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.roadwise.app.core.Prefs
import java.util.Locale
import java.util.UUID

/** Spoken guidance (on-device TTS, works offline) with audio ducking for music apps. */
class Voice(ctx: Context, private val prefs: Prefs) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var ready = false
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var tone: ToneGenerator? = null
    private var pending = 0

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            tts.setAudioAttributes(attrs)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { release() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { release() }
            })
        }
    }

    @Synchronized private fun release() {
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) am.abandonAudioFocusRequest(focus)
    }

    fun speak(text: String, interrupt: Boolean = false) {
        if (!prefs.current.voice || !ready || text.isBlank()) return
        synchronized(this) { pending++ }
        am.requestAudioFocus(focus)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, UUID.randomUUID().toString())
    }

    fun chime() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (_: Exception) {}
    }

    fun stop() { if (ready) tts.stop() }
}
