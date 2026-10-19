package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** The upstream preference keys are retained across both old and new AirPlayPersistence versions. */
object VoyahMediaSettings {
    enum class Option(val key: String, val title: String, val hint: String) {
        SMOOTH_VIDEO("smooth_video", "Плавное видео",
            "Выравнивать время показа кадров. Использует прямой вывод; добавляет небольшую задержку."),
        DIRECT_VIDEO("direct_video_output", "Прямой вывод видео",
            "Вывод через SurfaceView. Настройка яркости и цвета изображения при этом недоступна."),
        LOW_LATENCY("low_latency_decoder", "Декодирование с низкой задержкой",
            "Дополнительные параметры аппаратного декодера. При отказе используется обычная конфигурация."),
        CALL_ECHO("call_echo_cancellation", "Подавление эха в звонках",
            "Подавлять возврат звука собеседнику средствами VoyahPlay."),
        CALL_VOICE("call_voice_filter", "Более чёткие голоса в звонках",
            "Уменьшать низкие частоты в звуке звонка."),
        USB_SPEECH("speech_without_8_16_khz", "Более чёткий голос навигации по USB",
            "Не предлагать iPhone форматы 8 и 16 кГц для подсказок и предупреждений."),
        BLUETOOTH_AUDIO("car_bluetooth_audio", "Звук CarPlay через Bluetooth автомобиля",
            "Не предлагать вывод звука через CarPlay. iPhone должен быть подключён к Bluetooth автомобиля."),
    }

    private val videoOptions = listOf(Option.SMOOTH_VIDEO, Option.DIRECT_VIDEO, Option.LOW_LATENCY)
    private val audioOptions = listOf(Option.CALL_ECHO, Option.CALL_VOICE, Option.USB_SPEECH, Option.BLUETOOTH_AUDIO)
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun enabled(context: Context, option: Option): Boolean = prefs(context).getBoolean(option.key, false)
    fun setEnabled(context: Context, option: Option, enabled: Boolean) {
        prefs(context).edit().putBoolean(option.key, enabled).apply()
    }

    /** Only a changed output type requires the Activity's video View to be rebuilt. */
    fun surfaceKey(context: Context): Int =
        (if (enabled(context, Option.SMOOTH_VIDEO)) 1 else 0) or
            (if (enabled(context, Option.DIRECT_VIDEO)) 2 else 0)

    /** Stored in the sink so a surviving background session cannot silently reuse old options. */
    fun sessionKey(context: Context): String = Option.entries.joinToString("") {
        if (enabled(context, it)) "1" else "0"
    }

    fun addVideo(activity: Activity, parent: LinearLayout, theme: SettingsTheme) =
        addOptions(activity, parent, theme, videoOptions)

    fun addAudio(activity: Activity, parent: LinearLayout, theme: SettingsTheme) {
        addOptions(activity, parent, theme, audioOptions)
        addSwitch(activity, parent, theme, "Телефонный звук автомобиля для звонков CarPlay",
            "Экспериментально: включать телефонный маршрут Android на время звонка iPhone. Проверяется отдельно для Voyah.",
            BydOutputSettings.phoneAudioRoute(activity)) {
            BydOutputSettings.setPhoneAudioRoute(activity, it)
            PhoneAudioRoute.settingsChanged(activity)
        }
        addSwitch(activity, parent, theme, "Телефонный звук автомобиля для VoIP-звонков в других приложениях",
            "Экспериментально: следить за голосовыми звонками, пока работает VoyahPlay. Доступность зависит от Android мультимедиа.",
            BydOutputSettings.phoneAudioRouteApps(activity)) {
            BydOutputSettings.setPhoneAudioRouteApps(activity, it)
            PhoneAudioRoute.settingsChanged(activity)
        }
    }

    private fun addOptions(activity: Activity, parent: LinearLayout, theme: SettingsTheme, options: List<Option>) {
        for (option in options) addSwitch(activity, parent, theme, option.title,
            option.hint + " Применяется после переподключения CarPlay.", enabled(activity, option)) {
            setEnabled(activity, option, it)
        }
    }

    private fun addSwitch(activity: Activity, parent: LinearLayout, theme: SettingsTheme,
        title: String, hint: String, checked: Boolean, save: (Boolean) -> Unit) {
        parent.addView(SettingsWidgets.createSwitchRow(activity, title, hint, checked, theme,
            onChanged = save).rowView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = theme.dp(activity, 12)
        })
    }
}
