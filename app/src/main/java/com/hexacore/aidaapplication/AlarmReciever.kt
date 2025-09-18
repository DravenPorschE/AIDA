package com.hexacore.aidaapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.media.MediaPlayer  // ✅ this fixes unresolved reference

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val afd = context.assets.openFd("alarm_tone.mp3")
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = false   // 🔹 play only once
                prepare()
                start()
                // 🔹 release after completion
                setOnCompletionListener {
                    it.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

