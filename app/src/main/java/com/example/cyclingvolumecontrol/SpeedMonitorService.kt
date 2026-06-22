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

    // 省电：自适应 GPS 策略
    private var stationaryCount = 0
    private var isLowPowerMode = false
    private var lastNotificationTime = 0L
    private var lastSetVolumeIndex = -1
    private var maxVolumeIndex = -1

    companion object {
        const val CHANNEL_ID = "SpeedMonitorServiceChannel"
        var minSpeed = 5f
        var maxSpeed = 30f
        var minVolRatio = 0.2f
        var maxVolRatio = 1.0f
        var maxHistorySize = 1
        var persistentNotification = false

        // Activity 轮询用：Service 每次计算后更新，Activity 定时读取
        @Volatile var lastSpeed = 0f
        @Volatile var lastVolumePercent = 0

        // 省电：自适应 GPS 参数
        const val NORMAL_INTERVAL = 1000L
        const val NORMAL_DISTANCE = 5f
        const val LOW_POWER_INTERVAL = 10000L
        const val LOW_POWER_DISTANCE = 50f
        const val STATIONARY_SPEED_THRESHOLD = 0.5f
        const val STATIONARY_COUNT_THRESHOLD = 5
        const val NOTIFICATION_THROTTLE_MS = 5000L
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

            // 静止检测：连续低速则切换到省电模式
            if (averageSpeed < STATIONARY_SPEED_THRESHOLD) {
                stationaryCount++
                if (stationaryCount >= STATIONARY_COUNT_THRESHOLD && !isLowPowerMode) {
                    isLowPowerMode = true
                    applyGpsPolicy()
                }
            } else {
                stationaryCount = 0
                if (isLowPowerMode) {
                    isLowPowerMode = false
                    applyGpsPolicy()
                }
            }

            // 调用计算逻辑
            val ratio = calculateVolumeRatio(averageSpeed)
            val volumePct = (ratio * 100).toInt()

            // 音量去重：目标值没变就不调 setStreamVolume，省掉 AudioManager IPC
            val targetIndex = (maxVolumeIndex * ratio).toInt()
            if (targetIndex != lastSetVolumeIndex) {
                lastSetVolumeIndex = targetIndex
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
            }

            // 写入静态字段供 Activity 轮询
            lastSpeed = averageSpeed
            lastVolumePercent = volumePct

            if (persistentNotification) {
                val now = System.currentTimeMillis()
                if (now - lastNotificationTime >= NOTIFICATION_THROTTLE_MS) {
                    lastNotificationTime = now
                    updateNotification(averageSpeed, volumePct)
                }
            }
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
        maxVolumeIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
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
        applyGpsPolicy()
    }

    private fun applyGpsPolicy() {
        try {
            locationManager.removeUpdates(locationListener)
            val (interval, distance) = if (isLowPowerMode) {
                LOW_POWER_INTERVAL to LOW_POWER_DISTANCE
            } else {
                NORMAL_INTERVAL to NORMAL_DISTANCE
            }
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                interval,
                distance,
                locationListener
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun updateNotification(speed: Float, volumePercent: Int) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("骑行音量控制中")
            .setContentText("速度: %.1f km/h  |  音量: %d%%".format(speed, volumePercent))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
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