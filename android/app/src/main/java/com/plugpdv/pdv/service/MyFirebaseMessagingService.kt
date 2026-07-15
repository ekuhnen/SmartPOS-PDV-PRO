package com.plugpdv.pdv.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.plugpdv.pdv.R
import com.plugpdv.pdv.ui.auth.LoginActivity
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.ForceLogoutBus
import com.plugpdv.pdv.utils.KillSwitchManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var supabase: SupabaseClient
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Tratamento de Kill-Switch (data payload silencioso)
        when (remoteMessage.data["action"]) {
            "force_logout" -> {
                val reason = remoteMessage.data["reason"] ?: "Sessão encerrada pelo administrador"
                Log.w(TAG, "Received force_logout via FCM: $reason")
                // Emitir no bus - coletado pela BaseActivity ativa
                ForceLogoutBus.emit(reason)
                // Fallback direto caso não haja Activity ativa (app em background)
                KillSwitchManager.forceLogout(applicationContext, reason)
                return
            }
        }

        // Checa se a mensagem contém payload de notificação normal
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            sendNotification(it.title ?: "SmartPOS PDV", it.body ?: "")
        }

        // Fallback: toast para pushes de dados sem action reconhecida
        if (remoteMessage.data.isNotEmpty() && remoteMessage.data["action"] == null) {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Refreshed FCM token: $token")
        // Atualizar token na tabela pdv_devices
        val deviceId = DeviceIdProvider.get(applicationContext)
        scope.launch {
            try {
                supabase.from("pdv_devices").update(
                    { set("fcm_token", token) }
                ) {
                    filter { eq("id", deviceId) }
                }
                Log.i(TAG, "FCM token updated on server for device: $deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update FCM token on server", e)
            }
        }
    }

    private fun sendNotification(title: String, messageBody: String) {
        val intent = Intent(this, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val channelId = "fcm_default_channel"
        val defaultSoundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_push)
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Notificações do Sistema",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0, notificationBuilder.build())
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }
}

