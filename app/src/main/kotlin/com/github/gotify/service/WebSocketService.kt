package com.github.gotify.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.gotify.MarkwonFactory
import com.github.gotify.MissedMessageUtil
import com.github.gotify.NotificationSupport
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.api.Callback
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.api.MessageApi
import com.github.gotify.client.model.Message
import com.github.gotify.log.Log
import com.github.gotify.log.UncaughtExceptionHandler
import com.github.gotify.messages.Extras
import com.github.gotify.messages.MessagesActivity
import com.github.gotify.picasso.PicassoHandler
import io.noties.markwon.Markwon
import java.util.concurrent.atomic.AtomicLong

internal class WebSocketService : Service() {
    companion object {
        val NEW_MESSAGE_BROADCAST = "${WebSocketService::class.java.name}.NEW_MESSAGE"
        private const val NOT_LOADED = -2L
    }

    private lateinit var settings: Settings
    private var connection: WebSocketConnection? = null

    private val lastReceivedMessage = AtomicLong(NOT_LOADED)
    private lateinit var missingMessageUtil: MissedMessageUtil

    private lateinit var picassoHandler: PicassoHandler
    private lateinit var markwon: Markwon

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        val client = ClientFactory.clientToken(
            settings.url,
            settings.sslSettings(),
            settings.token
        )
        missingMessageUtil = MissedMessageUtil(client.createService(MessageApi::class.java))
        Log.i("Create ${javaClass.simpleName}")
        picassoHandler = PicassoHandler(this, settings)
        markwon = MarkwonFactory.createForNotification(this, picassoHandler.get())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (connection != null) {
            connection!!.close()
        }
        Log.w("Destroy ${javaClass.simpleName}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.init(this)
        if (connection != null) {
            connection!!.close()
        }
        Log.i("Starting ${javaClass.simpleName}")
        super.onStartCommand(intent, flags, startId)
        Thread { startPushService() }.start()

        return START_STICKY
    }

    private fun startPushService() {
        UncaughtExceptionHandler.registerCurrentThread()
        showForegroundNotification(getString(R.string.websocket_init))

        if (lastReceivedMessage.get() == NOT_LOADED) {
            missingMessageUtil.lastReceivedMessage { lastReceivedMessage.set(it) }
        }

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        connection = WebSocketConnection(
            settings.url,
            settings.sslSettings(),
            settings.token,
            cm,
            alarmManager
        )
            .onOpen { onOpen() }
            .onClose { onClose() }
            .onBadRequest { message -> onBadRequest(message) }
            .onNetworkFailure { minutes -> onNetworkFailure(minutes) }
            .onMessage { message -> onMessage(message) }
            .onReconnected { notifyMissedNotifications() }
            .start()

        val intentFilter = IntentFilter()
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)

        picassoHandler.updateAppIds()
    }

    private fun onClose() {
        showForegroundNotification(
            getString(R.string.websocket_closed),
            getString(R.string.websocket_reconnect)
        )
        ClientFactory.userApiWithToken(settings)
            .currentUser()
            .enqueue(
                Callback.call(
                    onSuccess = { doReconnect() },
                    onError = { exception ->
                        if (exception.code == 401) {
                            showForegroundNotification(
                                getString(R.string.user_action),
                                getString(R.string.websocket_closed_logout)
                            )
                        } else {
                            Log.i(
                                "WebSocket closed but the user still authenticated, " +
                                    "trying to reconnect"
                            )
                            doReconnect()
                        }
                    }
                )
            )
    }

    private fun doReconnect() {
        if (connection == null) {
            return
        }
        connection!!.scheduleReconnect(15)
    }

    private fun onBadRequest(message: String) {
        showForegroundNotification(getString(R.string.websocket_could_not_connect), message)
    }

    private fun onNetworkFailure(minutes: Int) {
        val status = getString(R.string.websocket_not_connected)
        val intervalUnit = resources
            .getQuantityString(R.plurals.websocket_retry_interval, minutes, minutes)
        showForegroundNotification(
            status,
            "${getString(R.string.websocket_reconnect)} $intervalUnit"
        )
    }

    private fun onOpen() {
        showForegroundNotification(getString(R.string.websocket_listening))
    }

    private fun notifyMissedNotifications() {
        val messageId = lastReceivedMessage.get()
        if (messageId == NOT_LOADED) {
            return
        }

        val messages = missingMessageUtil.missingMessages(messageId).filterNotNull()

        if (messages.size > 5) {
            onGroupedMessages(messages)
        } else {
            messages.forEach {
                onMessage(it)
            }
        }
    }

    private fun onGroupedMessages(messages: List<Message>) {
        var highestPriority = 0L
        messages.forEach { message ->
            if (lastReceivedMessage.get() < message.id) {
                lastReceivedMessage.set(message.id)
                highestPriority = highestPriority.coerceAtLeast(message.priority)
            }
            broadcast(message)
        }
        val size = messages.size
        showNotification(
            NotificationSupport.ID.GROUPED,
            getString(R.string.missed_messages),
            getString(R.string.grouped_message, size),
            highestPriority,
            null
        )
    }

    private fun onMessage(message: Message) {
        if (lastReceivedMessage.get() < message.id) {
            lastReceivedMessage.set(message.id)
        }
        broadcast(message)
        showNotification(
            message.id,
            message.title,
            message.message,
            message.priority,
            message.extras,
            message.appid
        )
    }

    private fun broadcast(message: Message) {
        val intent = Intent()
        intent.action = NEW_MESSAGE_BROADCAST
        intent.putExtra("message", Utils.JSON.toJson(message))
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun showForegroundNotification(title: String, message: String? = null) {
        val notificationIntent = Intent(this, MessagesActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notificationBuilder =
            NotificationCompat.Builder(this, NotificationSupport.Channel.FOREGROUND)
        notificationBuilder.setSmallIcon(R.drawable.ic_gotify)
        notificationBuilder.setOngoing(true)
        notificationBuilder.priority = NotificationCompat.PRIORITY_MIN
        notificationBuilder.setShowWhen(false)
        notificationBuilder.setWhen(0)
        notificationBuilder.setContentTitle(title)

        if (message != null) {
            notificationBuilder.setContentText(message)
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }

        notificationBuilder.setContentIntent(pendingIntent)
        notificationBuilder.color = ContextCompat.getColor(applicationContext, R.color.colorPrimary)
        startForeground(NotificationSupport.ID.FOREGROUND, notificationBuilder.build())
    }

    private fun showNotification(
        id: Int,
        title: String,
        message: String,
        priority: Long,
        extras: Map<String, Any>?
    ) {
        showNotification(id.toLong(), title, message, priority, extras, -1L)
    }

    private fun showNotification(
        id: Long,
        title: String,
        message: String,
        priority: Long,
        extras: Map<String, Any>?,
        appId: Long
    ) {
        var intent: Intent

        val intentUrl = Extras.getNestedValue(
            String::class.java,
            extras,
            "android::action",
            "onReceive",
            "intentUrl"
        )

        if (intentUrl != null) {
            intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(intentUrl)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        val url = Extras.getNestedValue(
            String::class.java,
            extras,
            "client::notification",
            "click",
            "url"
        )

        if (url != null) {
            intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
        } else {
            intent = Intent(this, MessagesActivity::class.java)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(
            this,
            NotificationSupport.convertPriorityToChannel(priority)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            showNotificationGroup(priority)
        }

        b.setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_gotify)
            .setLargeIcon(picassoHandler.getIcon(appId))
            .setTicker("${getString(R.string.app_name)} - $title")
            .setGroup(NotificationSupport.Group.MESSAGES)
            .setContentTitle(title)
            .setDefaults(Notification.DEFAULT_LIGHTS or Notification.DEFAULT_SOUND)
            .setLights(Color.CYAN, 1000, 5000)
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(contentIntent)

        var formattedMessage = message as CharSequence
        var newMessage: String? = null
        if (Extras.useMarkdown(extras)) {
            formattedMessage = markwon.toMarkdown(message)
            newMessage = formattedMessage.toString()
        }
        b.setContentText(newMessage ?: message)
        b.setStyle(NotificationCompat.BigTextStyle().bigText(formattedMessage))

        val notificationImageUrl = Extras.getNestedValue(
            String::class.java,
            extras,
            "client::notification",
            "bigImageUrl"
        )

        if (notificationImageUrl != null) {
            try {
                b.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(picassoHandler.getImageFromUrl(notificationImageUrl))
                )
            } catch (e: Exception) {
                Log.e("Error loading bigImageUrl", e)
            }
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Utils.longToInt(id), b.build())
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun showNotificationGroup(priority: Long) {
        val intent = Intent(this, MessagesActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            this,
            NotificationSupport.convertPriorityToChannel(priority)
        )

        builder.setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_gotify)
            .setTicker(getString(R.string.app_name))
            .setGroup(NotificationSupport.Group.MESSAGES)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentTitle(getString(R.string.grouped_notification_text))
            .setGroupSummary(true)
            .setContentText(getString(R.string.grouped_notification_text))
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(contentIntent)

        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(-5, builder.build())
    }
}
