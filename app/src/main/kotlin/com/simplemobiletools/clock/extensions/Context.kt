package com.simplemobiletools.clock.extensions

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager.STREAM_ALARM
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.widget.Toast
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import com.simplemobiletools.clock.R
import com.simplemobiletools.clock.activities.ReminderActivity
import com.simplemobiletools.clock.activities.SnoozeReminderActivity
import com.simplemobiletools.clock.activities.SplashActivity
import com.simplemobiletools.clock.databases.AppDatabase
import com.simplemobiletools.clock.helpers.*
import com.simplemobiletools.clock.interfaces.TimerDao
import com.simplemobiletools.clock.models.Alarm
import com.simplemobiletools.clock.models.MyTimeZone
import com.simplemobiletools.clock.models.Timer
import com.simplemobiletools.clock.models.TimerState
import com.simplemobiletools.clock.receivers.AlarmReceiver
import com.simplemobiletools.clock.receivers.HideAlarmReceiver
import com.simplemobiletools.clock.receivers.HideTimerReceiver
import com.simplemobiletools.clock.services.SnoozeService
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import java.util.*
import kotlin.math.pow

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.dbHelper: DBHelper get() = DBHelper.newInstance(applicationContext)
val Context.timerDb: TimerDao get() = AppDatabase.getInstance(applicationContext).TimerDao()
val Context.timerHelper: TimerHelper get() = TimerHelper(this)

fun Context.getFormattedDate(calendar: Calendar): String {
    val dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7    // make sure index 0 means monday
    val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
    val month = calendar.get(Calendar.MONTH)

    val dayString = resources.getStringArray(R.array.week_days_short)[dayOfWeek]
    val monthString = resources.getStringArray(R.array.months)[month]
    return "$dayString, $dayOfMonth $monthString"
}

fun Context.getEditedTimeZonesMap(): HashMap<Int, String> {
    val editedTimeZoneTitles = config.editedTimeZoneTitles
    val editedTitlesMap = HashMap<Int, String>()
    editedTimeZoneTitles.forEach {
        val parts = it.split(EDITED_TIME_ZONE_SEPARATOR.toRegex(), 2)
        editedTitlesMap[parts[0].toInt()] = parts[1]
    }
    return editedTitlesMap
}

fun Context.getAllTimeZonesModified(): ArrayList<MyTimeZone> {
    val timeZones = getAllTimeZones()
    val editedTitlesMap = getEditedTimeZonesMap()
    timeZones.forEach {
        if (editedTitlesMap.keys.contains(it.id)) {
            it.title = editedTitlesMap[it.id]!!
        } else {
            it.title = it.title.substring(it.title.indexOf(' ')).trim()
        }
    }
    return timeZones
}

fun Context.getModifiedTimeZoneTitle(id: Int) = getAllTimeZonesModified().firstOrNull { it.id == id }?.title ?: getDefaultTimeZoneTitle(id)

fun Context.createNewAlarm(timeInMinutes: Int, weekDays: Int): Alarm {
    val defaultAlarmSound = getDefaultAlarmSound(RingtoneManager.TYPE_ALARM)
    return Alarm(0, timeInMinutes, weekDays, false, false, defaultAlarmSound.title, defaultAlarmSound.uri, "")
}

fun Context.createNewTimer(): Timer {
    return Timer(
        null,
        config.timerSeconds,
        TimerState.Idle,
        config.timerVibrate,
        config.timerSoundUri,
        config.timerSoundTitle,
        config.timerLabel ?: "",
        System.currentTimeMillis(),
        config.timerChannelId,
    )
}

fun Context.scheduleNextAlarm(alarm: Alarm, showToast: Boolean) {
    val calendar = Calendar.getInstance()
    calendar.firstDayOfWeek = Calendar.MONDAY
    val currentTimeInMinutes = getCurrentDayMinutes()

    if (alarm.days == TODAY_BIT) {
        val triggerInMinutes = alarm.timeInMinutes - currentTimeInMinutes
        setupAlarmClock(alarm, triggerInMinutes * 60 - calendar.get(Calendar.SECOND))

        if (showToast) {
            showRemainingTimeMessage(triggerInMinutes)
        }
    } else if (alarm.days == TOMORROW_BIT) {
        val triggerInMinutes = alarm.timeInMinutes - currentTimeInMinutes + DAY_MINUTES
        setupAlarmClock(alarm, triggerInMinutes * 60 - calendar.get(Calendar.SECOND))

        if (showToast) {
            showRemainingTimeMessage(triggerInMinutes)
        }
    } else {
        for (i in 0..7) {
            val currentDay = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val isCorrectDay = alarm.days and 2.0.pow(currentDay).toInt() != 0
            if (isCorrectDay && (alarm.timeInMinutes > currentTimeInMinutes || i > 0)) {
                val triggerInMinutes = alarm.timeInMinutes - currentTimeInMinutes + (i * DAY_MINUTES)
                setupAlarmClock(alarm, triggerInMinutes * 60 - calendar.get(Calendar.SECOND))

                if (showToast) {
                    showRemainingTimeMessage(triggerInMinutes)
                }
                break
            } else {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }
}

fun Context.showRemainingTimeMessage(totalMinutes: Int) {
    val fullString = String.format(getString(R.string.time_remaining), formatMinutesToTimeString(totalMinutes))
    toast(fullString, Toast.LENGTH_LONG)
}

fun Context.setupAlarmClock(alarm: Alarm, triggerInSeconds: Int) {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val targetMS = System.currentTimeMillis() + triggerInSeconds * 1000
    try {
        AlarmManagerCompat.setAlarmClock(alarmManager, targetMS, getOpenAlarmTabIntent(), getAlarmIntent(alarm))
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.getOpenAlarmTabIntent(): PendingIntent {
    val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
    intent.putExtra(OPEN_TAB, TAB_ALARM)
    return PendingIntent.getActivity(this, OPEN_ALARMS_TAB_INTENT_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.getOpenTimerTabIntent(timerId: Int): PendingIntent {
    val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
    intent.putExtra(OPEN_TAB, TAB_TIMER)
    intent.putExtra(TIMER_ID, timerId)
    return PendingIntent.getActivity(this, timerId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.getOpenStopwatchTabIntent(): PendingIntent {
    val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
    intent.putExtra(OPEN_TAB, TAB_STOPWATCH)
    return PendingIntent.getActivity(this, OPEN_STOPWATCH_TAB_INTENT_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.getAlarmIntent(alarm: Alarm): PendingIntent {
    val intent = Intent(this, AlarmReceiver::class.java)
    intent.putExtra(ALARM_ID, alarm.id)
    return PendingIntent.getBroadcast(this, alarm.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.cancelAlarmClock(alarm: Alarm) {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(getAlarmIntent(alarm))
}

fun Context.hideNotification(id: Int) {
    val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.cancel(id)
}

fun Context.deleteNotificationChannel(channelId: String) {
    if (isOreoPlus()) {
        try {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.deleteNotificationChannel(channelId)
        } catch (_: Throwable) {}
    }
}

fun Context.hideTimerNotification(timerId: Int) = hideNotification(timerId)

fun Context.updateWidgets() {
    updateDigitalWidgets()
    updateAnalogueWidgets()
}

fun Context.updateDigitalWidgets() {
    val component = ComponentName(applicationContext, MyDigitalTimeWidgetProvider::class.java)
    val widgetIds = AppWidgetManager.getInstance(applicationContext)?.getAppWidgetIds(component) ?: return
    if (widgetIds.isNotEmpty()) {
        val ids = intArrayOf(R.xml.widget_digital_clock_info)
        Intent(applicationContext, MyDigitalTimeWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(this)
        }
    }
}

fun Context.updateAnalogueWidgets() {
    val component = ComponentName(applicationContext, MyAnalogueTimeWidgetProvider::class.java)
    val widgetIds = AppWidgetManager.getInstance(applicationContext)?.getAppWidgetIds(component) ?: return
    if (widgetIds.isNotEmpty()) {
        val ids = intArrayOf(R.xml.widget_analogue_clock_info)
        Intent(applicationContext, MyAnalogueTimeWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(this)
        }
    }
}

fun Context.getFormattedTime(passedSeconds: Int, showSeconds: Boolean, makeAmPmSmaller: Boolean): SpannableString {
    val use24HourFormat = DateFormat.is24HourFormat(this)
    val hours = (passedSeconds / 3600) % 24
    val minutes = (passedSeconds / 60) % 60
    val seconds = passedSeconds % 60

    return if (use24HourFormat) {
        val formattedTime = formatTime(showSeconds, use24HourFormat, hours, minutes, seconds)
        SpannableString(formattedTime)
    } else {
        val formattedTime = formatTo12HourFormat(showSeconds, hours, minutes, seconds)
        val spannableTime = SpannableString(formattedTime)
        val amPmMultiplier = if (makeAmPmSmaller) 0.4f else 1f
        spannableTime.setSpan(RelativeSizeSpan(amPmMultiplier), spannableTime.length - 3, spannableTime.length, 0)
        spannableTime
    }
}

fun Context.formatTo12HourFormat(showSeconds: Boolean, hours: Int, minutes: Int, seconds: Int): String {
    val appendable = getString(if (hours >= 12) R.string.p_m else R.string.a_m)
    val newHours = if (hours == 0 || hours == 12) 12 else hours % 12
    return "${formatTime(showSeconds, false, newHours, minutes, seconds)} $appendable"
}

fun Context.getNextAlarm(): String {
    val milliseconds = (getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime ?: return ""
    val calendar = Calendar.getInstance()
    val isDaylightSavingActive = TimeZone.getDefault().inDaylightTime(Date())
    var offset = calendar.timeZone.rawOffset
    if (isDaylightSavingActive) {
        offset += TimeZone.getDefault().dstSavings
    }

    calendar.timeInMillis = milliseconds
    val dayOfWeekIndex = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val dayOfWeek = resources.getStringArray(R.array.week_days_short)[dayOfWeekIndex]
    val formatted = getFormattedTime(((milliseconds + offset) / 1000L).toInt(), false, false)
    return "$dayOfWeek $formatted"
}

fun Context.rescheduleEnabledAlarms() {
    dbHelper.getEnabledAlarms().forEach {
        if (it.days != TODAY_BIT || it.timeInMinutes > getCurrentDayMinutes()) {
            scheduleNextAlarm(it, false)
        }
    }
}

fun Context.isScreenOn() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isScreenOn

fun Context.showAlarmNotification(alarm: Alarm) {
    val pendingIntent = getOpenAlarmTabIntent()
    val notification = getAlarmNotification(pendingIntent, alarm)
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    try {
        notificationManager.notify(alarm.id, notification)
    } catch (e: Exception) {
        showErrorToast(e)
    }

    if (alarm.days > 0) {
        scheduleNextAlarm(alarm, false)
    }
}

fun Context.getTimerNotification(timer: Timer, pendingIntent: PendingIntent, addDeleteIntent: Boolean): Notification {
    var soundUri = timer.soundUri
    if (soundUri == SILENT) {
        soundUri = ""
    } else {
        grantReadUriPermission(soundUri)
    }

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = timer.channelId ?: "simple_timer_channel_${soundUri}_${System.currentTimeMillis()}"
    timerHelper.insertOrUpdateTimer(timer.copy(channelId = channelId))

    if (isOreoPlus()) {
        try {
            notificationManager.deleteNotificationChannel(channelId)
        } catch (e: Exception) {
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(STREAM_ALARM)
            .build()

        val name = getString(R.string.timer)
        val importance = NotificationManager.IMPORTANCE_HIGH
        NotificationChannel(channelId, name, importance).apply {
            setBypassDnd(true)
            enableLights(true)
            lightColor = getProperPrimaryColor()
            setSound(Uri.parse(soundUri), audioAttributes)

            if (!timer.vibrate) {
                vibrationPattern = longArrayOf(0L)
            }

            enableVibration(timer.vibrate)
            notificationManager.createNotificationChannel(this)
        }
    }

    val title = if (timer.label.isEmpty()) {
        getString(R.string.timer)
    } else {
        timer.label
    }

    val reminderActivityIntent = getReminderActivityIntent()
    val builder = NotificationCompat.Builder(this)
        .setContentTitle(title)
        .setContentText(getString(R.string.time_expired))
        .setSmallIcon(R.drawable.ic_hourglass_vector)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setDefaults(Notification.DEFAULT_LIGHTS)
        .setCategory(Notification.CATEGORY_EVENT)
        .setAutoCancel(true)
        .setSound(Uri.parse(soundUri), STREAM_ALARM)
        .setChannelId(channelId)
        .addAction(
            R.drawable.ic_cross_vector,
            getString(R.string.dismiss),
            if (addDeleteIntent) {
                reminderActivityIntent
            } else {
                getHideTimerPendingIntent(timer.id!!)
            }
        )

    if (addDeleteIntent) {
        builder.setDeleteIntent(reminderActivityIntent)
    }

    builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    if (timer.vibrate) {
        val vibrateArray = LongArray(2) { 500 }
        builder.setVibrate(vibrateArray)
    }

    val notification = builder.build()
    notification.flags = notification.flags or Notification.FLAG_INSISTENT
    return notification
}

fun Context.getHideTimerPendingIntent(timerId: Int): PendingIntent {
    val intent = Intent(this, HideTimerReceiver::class.java)
    intent.putExtra(TIMER_ID, timerId)
    return PendingIntent.getBroadcast(this, timerId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.getHideAlarmPendingIntent(alarm: Alarm, channelId: String): PendingIntent {
    val intent = Intent(this, HideAlarmReceiver::class.java).apply {
        putExtra(ALARM_ID, alarm.id)
        putExtra(ALARM_NOTIFICATION_CHANNEL_ID, channelId)
    }
    return PendingIntent.getBroadcast(this, alarm.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.getAlarmNotification(pendingIntent: PendingIntent, alarm: Alarm): Notification {
    val soundUri = alarm.soundUri
    if (soundUri != SILENT) {
        grantReadUriPermission(soundUri)
    }
    val channelId = "simple_alarm_channel_${soundUri}_${alarm.vibrate}"
    val label = alarm.label.ifEmpty {
        getString(R.string.alarm)
    }

    if (isOreoPlus()) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(STREAM_ALARM)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val importance = NotificationManager.IMPORTANCE_HIGH
        NotificationChannel(channelId, label, importance).apply {
            setBypassDnd(true)
            enableLights(true)
            lightColor = getProperPrimaryColor()
            enableVibration(alarm.vibrate)
            setSound(Uri.parse(soundUri), audioAttributes)
            notificationManager.createNotificationChannel(this)
        }
    }

    val dismissIntent = getHideAlarmPendingIntent(alarm, channelId)
    val builder = NotificationCompat.Builder(this)
        .setContentTitle(label)
        .setContentText(getFormattedTime(getPassedSeconds(), false, false))
        .setSmallIcon(R.drawable.ic_alarm_vector)
        .setContentIntent(pendingIntent)
        .setPriority(Notification.PRIORITY_HIGH)
        .setDefaults(Notification.DEFAULT_LIGHTS)
        .setAutoCancel(true)
        .setChannelId(channelId)
        .addAction(R.drawable.ic_snooze_vector, getString(R.string.snooze), getSnoozePendingIntent(alarm))
        .addAction(R.drawable.ic_cross_vector, getString(R.string.dismiss), dismissIntent)
        .setDeleteIntent(dismissIntent)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    if (soundUri != SILENT) {
        builder.setSound(Uri.parse(soundUri), STREAM_ALARM)
    }

    if (alarm.vibrate) {
        val vibrateArray = LongArray(2) { 500 }
        builder.setVibrate(vibrateArray)
    }

    val notification = builder.build()
    notification.flags = notification.flags or Notification.FLAG_INSISTENT
    return notification
}

fun Context.getSnoozePendingIntent(alarm: Alarm): PendingIntent {
    val snoozeClass = if (config.useSameSnooze) SnoozeService::class.java else SnoozeReminderActivity::class.java
    val intent = Intent(this, snoozeClass).setAction("Snooze")
    intent.putExtra(ALARM_ID, alarm.id)
    return if (config.useSameSnooze) {
        PendingIntent.getService(this, alarm.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    } else {
        PendingIntent.getActivity(this, alarm.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

fun Context.getReminderActivityIntent(): PendingIntent {
    val intent = Intent(this, ReminderActivity::class.java)
    return PendingIntent.getActivity(this, REMINDER_ACTIVITY_INTENT_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.checkAlarmsWithDeletedSoundUri(uri: String) {
    val defaultAlarmSound = getDefaultAlarmSound(RingtoneManager.TYPE_ALARM)
    dbHelper.getAlarmsWithUri(uri).forEach {
        it.soundTitle = defaultAlarmSound.title
        it.soundUri = defaultAlarmSound.uri
        dbHelper.updateAlarm(it)
    }
}

fun Context.getAlarmSelectedDaysString(bitMask: Int): String {
    return when (bitMask) {
        TODAY_BIT -> getString(R.string.today)
        TOMORROW_BIT -> getString(R.string.tomorrow)
        EVERY_DAY_BIT -> getString(R.string.every_day)
        else -> getSelectedDaysString(bitMask)
    }
}

fun Context.firstDayOrder(bitMask: Int): Int {
    if (bitMask == TODAY_BIT) return -2
    if (bitMask == TOMORROW_BIT) return -1

    val dayBits = arrayListOf(MONDAY_BIT, TUESDAY_BIT, WEDNESDAY_BIT, THURSDAY_BIT, FRIDAY_BIT, SATURDAY_BIT, SUNDAY_BIT)

    val sundayFirst = baseConfig.isSundayFirst
    if (sundayFirst) {
        dayBits.moveLastItemToFront()
    }

    dayBits.forEach { bit ->
        if (bitMask and bit != 0) {
            return if (bit == SUNDAY_BIT && sundayFirst) 0 else bit
        }
    }

    return bitMask
}
