package dev.autobridge

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The steering-wheel voice button -> Google Assistant on the phone, through Android Auto.
 *
 * On Android Automotive a short press of that button only ever reaches the car's chosen digital
 * assistant, so AutoBridge offers itself as one (OCBM's route for Siri). Nothing changes until the
 * driver picks AutoBridge in Settings > Apps > Default apps > Digital assistant, which replaces the
 * truck's own Google Assistant.
 */
object VoiceAssistant {
    private const val SEARCH = 84  // Android Auto's voice key

    fun press(context: Context) {
        val service = BridgeService.instance
        if (service?.video == null) Log.w("AutoBridge", "voice button pressed with no phone connected")
        service?.key(SEARCH)
    }

    fun held(context: Context) = runCatching {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }.getOrDefault(false)

    /** The car's settings screen where the assistant is chosen, if it has one. */
    fun picker(context: Context): Intent? = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
        .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { runCatching { it.resolveActivity(context.packageManager) != null }.getOrDefault(false) }

    fun status(context: Context) = if (held(context)) "Voice button: Google Assistant on the phone (via Android Auto)"
        else "Voice button: the truck's assistant. To change it, pick AutoBridge as the Digital assistant."
}

class VoiceService : VoiceInteractionService()

class VoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Press(this)

    /** One button press: no window of our own, Android Auto's screen shows the Assistant. */
    private class Press(context: Context) : VoiceInteractionSession(context) {
        override fun onCreate() { super.onCreate(); setUiEnabled(false) }
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            VoiceAssistant.press(context)
            hide()
            finish()
        }
    }
}

/** Required alongside an assistant; speech is recognised on the phone, so every request is refused at once. */
class VoiceRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback) { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback) { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onStopListening(listener: Callback) { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
}
