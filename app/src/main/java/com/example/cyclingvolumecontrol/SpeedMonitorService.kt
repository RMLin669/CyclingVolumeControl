package com.example.cyclingvolumecontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SpeedMonitorService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private val speedHistory = mutableListOf<Float>()

    companion object {
        const val CHANNEL_ID = "SpeedMonitorServiceChannel"
        var minSpeed = 5f
        var maxSpeed = 30f
        var minVolRatio = 0.2f
        var maxVolRatio = 1.0f
        var maxHistorySize = 1
    }

    // 1. 定义 locationListener 对象 (解决 Unresolved reference 报错)
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val currentSpeed = location.speed * 3.6f
            speedHistory.add(currentSpeed)
            while (speedHistory.size > maxHistorySize) {
                speedHistory.removeAt(0)
            }
            val averageSpeed = speedHistory.average().toFloat()

            // 调用计算逻辑
            val ratio = calculateVolumeRatio(averageSpeed)

            val maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxIndex * ratio).toInt(), 0)
        }

        // 适配旧版本 Android 必须实现的空方法
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // 2. 抽取计算逻辑 (解决没有被调用的问题)
    private fun calculateVolumeRatio(speed: Float): Float {
        return if (minSpeed < maxSpeed) {
            when {
                speed <= minSpeed -> minVolRatio
                speed >= maxSpeed -> maxVolRatio
                else -> minVolRatio + (maxVolRatio - minVolRatio) * (speed - minSpeed) / (maxSpeed - minSpeed)
            }
        } else if (minSpeed > maxSpeed) {
            when {
                speed >= minSpeed -> minVolRatio
                speed <= maxSpeed -> maxVolRatio
                else -> minVolRatio + (maxVolRatio - minVolRatio) * (speed - minSpeed) / (maxSpeed - minSpeed)
            }
        } else {
            minVolRatio
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("骑行音量控制中")
            .setContentText("正在根据当前速度自动调节音量")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
        startLocationUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "速度监控服务频道",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}