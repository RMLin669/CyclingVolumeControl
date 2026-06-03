package com.example.cyclingvolumecontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    // 状态变量
    private var currentDevicePrefix = "NONE_"
    private var isMonitoring = false
    private var maxHistorySize = 1

    private lateinit var autoStartSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var persistNotifSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private val KEY_AUTO_START = "auto_start"
    private val KEY_PERSIST_NOTIF = "persist_notif"

    // UI 控件
    private lateinit var deviceTypeText: TextView
    private lateinit var btnPermissionFine: Button
    private lateinit var btnStart: Button
    private lateinit var speedText: TextView
    private lateinit var volumeText: TextView
    private lateinit var samplingSpinner: AutoCompleteTextView
    private val samplingOptions = arrayOf("1s (实时)", "3s (平滑)", "5s (极稳)")
    private lateinit var speedVolumeChart: SpeedVolumeChartView
    private lateinit var editMaxSpeedX: EditText
    private lateinit var heroCard: MaterialCardView

    // 服务相关
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager

    // 常量
    private val PREFS_NAME = "CyclingSettings"
    private val KEY_P1_SPEED  = "p1_speed"
    private val KEY_P1_VOL    = "p1_vol"
    private val KEY_P2_SPEED  = "p2_speed"
    private val KEY_P2_VOL    = "p2_vol"
    private val KEY_MAX_SPEED_X = "max_speed_x"
    private val KEY_SAMPLING  = "sampling_pos"
    private val KEY_THEME_COLOR = "theme_color"

    // 主题色预设
    private val themeColorMap = mapOf(
        R.id.themeBlue   to 0xFF1A6EF3.toInt(),
        R.id.themeGreen  to 0xFF4CAF50.toInt(),
        R.id.themeOrange to 0xFFFF9800.toInt(),
        R.id.themePurple to 0xFF9C27B0.toInt(),
        R.id.themeTeal   to 0xFF009688.toInt(),
    )

    private val requestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateUIState()
        if (persistNotifSwitch.isChecked) showPersistNotification()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 1. 初始化系统服务
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 2. 绑定 UI
        btnPermissionFine  = findViewById(R.id.btnPermissionFine)
        btnStart           = findViewById(R.id.btnStart)
        speedText          = findViewById(R.id.speedText)
        volumeText         = findViewById(R.id.volumeText)
        samplingSpinner    = findViewById(R.id.samplingSpinner)
        deviceTypeText     = findViewById(R.id.deviceTypeText)
        autoStartSwitch    = findViewById(R.id.autoStartSwitch)
        persistNotifSwitch = findViewById(R.id.persistNotifSwitch)
        speedVolumeChart   = findViewById(R.id.speedVolumeChart)
        editMaxSpeedX      = findViewById(R.id.editMaxSpeedX)
        heroCard           = findViewById(R.id.heroCard)

        // 3. 初始化主题色
        setupThemeColors()

        // 4. 初始化组件
        setupSamplingSpinner()
        setupSpeedVolumeChart()

        // 4. 开关监听
        autoStartSwitch.setOnClickListener {
            saveSettings()
            if (autoStartSwitch.isChecked && !isMonitoring) startSpeedMonitoring()
        }

        persistNotifSwitch.setOnClickListener {
            saveSettings()
            if (persistNotifSwitch.isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    showPersistNotification()
                }
            } else {
                cancelPersistNotification()
            }
            if (isMonitoring) syncToService()
        }

        // 5. 初始检测设备（会触发 loadSettingsForCurrentDevice）
        updateDeviceType()

        // 6. 注册音频设备实时监听
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateDeviceType() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateDeviceType() }
            }, null)
        }

        // 7. Window Insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnStart.setOnClickListener {
            if (isMonitoring) stopSpeedMonitoring() else startSpeedMonitoring()
        }

        updateUIState()

        // 8. 隐私说明弹窗
        findViewById<Button>(R.id.btnShowPrivacy).setOnClickListener {
            val privacyMessage = """
                🔒 隐私说明：
                本 App 获取的位置信息仅用于实时计算速度以调节音量，绝不会进行任何形式的上传或共享。如仍有担忧，您可以在手机系统的「设置」中手动关闭本 App 的联网权限，这不会影响速度监测功能。

                💡 使用建议：
                1. 在多任务界面「锁定」本 App。
                2. 将本 App 的省电策略修改为「无限制」。
                3. 关闭系统的全局省电模式。
                4. 若打开了「常驻通知」选项但是没有出现常驻通知，请授予本 App 通知权限。
                
                📚 使用说明：
                1. 当前速度与音量开始控制后才会实时更新。
                2. 控制过程中无法调节速度区间与音量区间，如需调节请先停止控制。
                3. 支持根据本机或者连接不同的蓝牙设备自动切换对应配置。
            """.trimIndent()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("隐私说明、使用建议与使用说明")
                .setMessage(privacyMessage)
                .setPositiveButton("我知道了", null)
                .show()
        }
    }

    private fun setupThemeColors() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_THEME_COLOR, 0xFF1A6EF3.toInt())
        applyThemeColor(saved)

        themeColorMap.forEach { (id, color) ->
            findViewById<View>(id).setOnClickListener {
                applyThemeColor(color)
            }
        }
    }

    private fun applyThemeColor(color: Int) {
        heroCard.setCardBackgroundColor(color)
        btnStart.backgroundTintList = ColorStateList.valueOf(color)
        speedVolumeChart.primaryColorOverride = color
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME_COLOR, color).apply()
    }

    private fun setupSpeedVolumeChart() {
        // 坐标图回调：点移动时保存并同步
        speedVolumeChart.onPointsChanged = { _, _, _, _ ->
            saveSettings()
            if (isMonitoring) syncToService()
        }

        // 最大速度输入框 - 点击弹出滚轮选择
        editMaxSpeedX.setOnClickListener {
            if (!isMonitoring) showMaxSpeedPicker()
        }
        editMaxSpeedX.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v in 10f..300f) {
                    speedVolumeChart.maxSpeedX = v
                    saveSettings()
                }
            }
        })
    }

    private fun showMaxSpeedPicker() {
        val current = editMaxSpeedX.text.toString().toIntOrNull()?.coerceIn(10, 300) ?: 40
        val dialogView = layoutInflater.inflate(R.layout.dialog_max_speed_picker, null)
        val tensPicker = dialogView.findViewById<NumberPicker>(R.id.pickerTens)
        val onesPicker = dialogView.findViewById<NumberPicker>(R.id.pickerOnes)

        tensPicker.apply {
            minValue = 0; maxValue = 9
            value = current / 10
            wrapSelectorWheel = true
        }
        onesPicker.apply {
            minValue = 0; maxValue = 9
            value = current % 10
            wrapSelectorWheel = true
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.MaxSpeedDialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val v = tensPicker.value * 10 + onesPicker.value
            editMaxSpeedX.setText(v.coerceIn(10, 300).toString())
            dialog.dismiss()
        }

        dialog.show()
        // 圆角弹窗
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
    }

    private fun setupSamplingSpinner() {
        samplingSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, samplingOptions))
        samplingSpinner.setOnItemClickListener { _, _, pos, _ ->
            maxHistorySize = when (pos) { 0 -> 1; 1 -> 3; 2 -> 5; else -> 1 }
            saveSettings()
            if (isMonitoring) syncToService()
        }
    }

    private fun updateDeviceType() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var deviceIdentifier = "PHONE"
        var displayName = "手机外放"

        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                val name = device.productName?.toString() ?: "未知蓝牙设备"
                deviceIdentifier = "BT_${name.replace(" ", "_")}"
                displayName = "蓝牙: $name"
                break
            }
        }

        val newPrefix = "${deviceIdentifier}_"

        if (newPrefix != currentDevicePrefix) {
            val wasMonitoring = isMonitoring
            if (wasMonitoring) stopSpeedMonitoring()

            currentDevicePrefix = newPrefix
            loadSettingsForCurrentDevice()

            if (wasMonitoring) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val willAutoStart = prefs.getBoolean(newPrefix + KEY_AUTO_START, false)
                if (willAutoStart) {
                    Toast.makeText(this, "检测到设备切换：$displayName (已根据配置自动重启控制)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "检测到设备切换：$displayName (新配置未开启自动控制，已停止)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "已切换至 $displayName 的配置", Toast.LENGTH_SHORT).show()
            }
        }
        deviceTypeText.text = "当前配置: $displayName"
    }

    /**
     * 从旧版本（NumberPicker + RangeSlider）的存储格式迁移到新格式。
     * 仅当新 key 不存在但旧 key 存在时执行一次，迁移后删除旧 key。
     * 旧格式：{prefix}min_ten / min_unit / max_ten / max_unit / vol_min / vol_max
     * 新格式：{prefix}p1_speed / p1_vol / p2_speed / p2_vol
     */
    private fun migrateLegacySettingsIfNeeded(prefs: android.content.SharedPreferences) {
        val newKeyExists = prefs.contains(currentDevicePrefix + KEY_P1_SPEED)
        if (newKeyExists) return // 已经是新格式，无需迁移

        val oldVolMinKey  = currentDevicePrefix + "vol_min"
        val oldVolMaxKey  = currentDevicePrefix + "vol_max"
        val oldMinTenKey  = currentDevicePrefix + "min_ten"
        val oldMinUnitKey = currentDevicePrefix + "min_unit"
        val oldMaxTenKey  = currentDevicePrefix + "max_ten"
        val oldMaxUnitKey = currentDevicePrefix + "max_unit"

        // 只要有任何旧 key 就迁移（首次安装时所有旧 key 都不存在，直接用默认值）
        val hasLegacy = prefs.contains(oldVolMinKey) || prefs.contains(oldMinTenKey)
        if (!hasLegacy) return

        val minTen  = prefs.getInt(oldMinTenKey,  0)
        val minUnit = prefs.getInt(oldMinUnitKey, 5)
        val maxTen  = prefs.getInt(oldMaxTenKey,  3)
        val maxUnit = prefs.getInt(oldMaxUnitKey, 0)
        val volMin  = prefs.getFloat(oldVolMinKey, 20f)
        val volMax  = prefs.getFloat(oldVolMaxKey, 100f)

        val p1Speed = (minTen * 10 + minUnit).toFloat()
        val p2Speed = (maxTen * 10 + maxUnit).toFloat()

        prefs.edit().apply {
            putFloat(currentDevicePrefix + KEY_P1_SPEED, p1Speed)
            putFloat(currentDevicePrefix + KEY_P1_VOL,   volMin)
            putFloat(currentDevicePrefix + KEY_P2_SPEED, p2Speed)
            putFloat(currentDevicePrefix + KEY_P2_VOL,   volMax)
            // 清除旧 key
            remove(oldMinTenKey); remove(oldMinUnitKey)
            remove(oldMaxTenKey); remove(oldMaxUnitKey)
            remove(oldVolMinKey); remove(oldVolMaxKey)
            apply()
        }
    }

    private fun saveSettings() {
        if (currentDevicePrefix == "NONE_") return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(currentDevicePrefix + KEY_P1_SPEED,   speedVolumeChart.point1Speed)
            putFloat(currentDevicePrefix + KEY_P1_VOL,     speedVolumeChart.point1Vol)
            putFloat(currentDevicePrefix + KEY_P2_SPEED,   speedVolumeChart.point2Speed)
            putFloat(currentDevicePrefix + KEY_P2_VOL,     speedVolumeChart.point2Vol)
            putFloat(currentDevicePrefix + KEY_MAX_SPEED_X, speedVolumeChart.maxSpeedX)
            putInt(currentDevicePrefix + KEY_SAMPLING, samplingOptions.indexOfFirst { it == samplingSpinner.text.toString() }.coerceAtLeast(0))
            putBoolean(currentDevicePrefix + KEY_AUTO_START, autoStartSwitch.isChecked)
            putBoolean(currentDevicePrefix + KEY_PERSIST_NOTIF, persistNotifSwitch.isChecked)
            apply()
        }
    }

    private fun loadSettingsForCurrentDevice() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 旧版数据迁移（v1.2 → v1.3+）
        migrateLegacySettingsIfNeeded(prefs)

        // 恢复图表点位
        speedVolumeChart.point1Speed = prefs.getFloat(currentDevicePrefix + KEY_P1_SPEED, 5f)
        speedVolumeChart.point1Vol   = prefs.getFloat(currentDevicePrefix + KEY_P1_VOL,   20f)
        speedVolumeChart.point2Speed = prefs.getFloat(currentDevicePrefix + KEY_P2_SPEED, 30f)
        speedVolumeChart.point2Vol   = prefs.getFloat(currentDevicePrefix + KEY_P2_VOL,   100f)
        val maxX = prefs.getFloat(currentDevicePrefix + KEY_MAX_SPEED_X, 40f)
        speedVolumeChart.maxSpeedX   = maxX

        // 同步最大速度输入框（不触发 TextWatcher 保存）
        editMaxSpeedX.setText(maxX.toInt().toString())

        // 恢复采样
        val savedSamplingPos = prefs.getInt(currentDevicePrefix + KEY_SAMPLING, 0)
        samplingSpinner.setText(samplingOptions[savedSamplingPos], false)
        maxHistorySize = when (savedSamplingPos) { 0 -> 1; 1 -> 3; 2 -> 5; else -> 1 }

        // 恢复开关状态
        val shouldAutoStart = prefs.getBoolean(currentDevicePrefix + KEY_AUTO_START, false)
        autoStartSwitch.isChecked = shouldAutoStart
        persistNotifSwitch.isChecked = prefs.getBoolean(currentDevicePrefix + KEY_PERSIST_NOTIF, false)

        // 自动开启判定
        val hasFineLocation = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (shouldAutoStart && !isMonitoring && hasFineLocation) {
            window.decorView.post { if (!isMonitoring) startSpeedMonitoring() }
        }

        if (isMonitoring) syncToService()
        updateUIState()
    }

    private fun syncToService() {
        SpeedMonitorService.minSpeed    = speedVolumeChart.point1Speed
        SpeedMonitorService.maxSpeed    = speedVolumeChart.point2Speed
        SpeedMonitorService.minVolRatio = speedVolumeChart.point1Vol / 100f
        SpeedMonitorService.maxVolRatio = speedVolumeChart.point2Vol / 100f
        SpeedMonitorService.maxHistorySize = maxHistorySize
        SpeedMonitorService.persistentNotification = persistNotifSwitch.isChecked
    }

    private fun updateUIState() {
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine) {
            btnPermissionFine.text = "✅"
            btnPermissionFine.isEnabled = false
            btnPermissionFine.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            if (!isMonitoring) btnStart.isEnabled = true
        } else {
            btnPermissionFine.text = "授权"
            btnPermissionFine.isEnabled = true
            btnPermissionFine.setOnClickListener {
                requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
            btnStart.isEnabled = false
        }

        val canEdit = !isMonitoring
        speedVolumeChart.isEnabled = canEdit
        editMaxSpeedX.isEnabled = canEdit
        samplingSpinner.isEnabled = canEdit
    }

    private fun startSpeedMonitoring() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
            syncToService()
            val intent = Intent(this, SpeedMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, uiLocationListener
            )
            isMonitoring = true
            btnStart.text = "⏹ 停止控制"
            updateUIState()
        }
    }

    private fun stopSpeedMonitoring() {
        stopService(Intent(this, SpeedMonitorService::class.java))
        locationManager.removeUpdates(uiLocationListener)
        isMonitoring = false
        btnStart.text = "开始控制音量"
        speedText.text = "0.0 km/h"
        volumeText.text = "当前音量: --%"
        updateUIState()
        if (persistNotifSwitch.isChecked) showPersistNotification()
    }

    private fun showPersistNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                SpeedMonitorService.CHANNEL_ID, "速度监控服务频道",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(channel)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, SpeedMonitorService.CHANNEL_ID)
            .setContentTitle("骑行音量控制")
            .setContentText("已就绪，开始控制后将实时显示速度和音量")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(1, notification)
    }

    private fun cancelPersistNotification() {
        if (!isMonitoring) {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(1)
        }
    }

    private val uiLocationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val s = l.speed * 3.6f
            speedText.text = "%.1f km/h".format(s)

            val p1Speed = speedVolumeChart.point1Speed
            val p1Vol   = speedVolumeChart.point1Vol / 100f
            val p2Speed = speedVolumeChart.point2Speed
            val p2Vol   = speedVolumeChart.point2Vol / 100f

            val ratio = if (p1Speed < p2Speed) {
                when {
                    s <= p1Speed -> p1Vol
                    s >= p2Speed -> p2Vol
                    else -> p1Vol + (p2Vol - p1Vol) * (s - p1Speed) / (p2Speed - p1Speed)
                }
            } else if (p1Speed > p2Speed) {
                when {
                    s >= p1Speed -> p1Vol
                    s <= p2Speed -> p2Vol
                    else -> p1Vol + (p2Vol - p1Vol) * (s - p1Speed) / (p2Speed - p1Speed)
                }
            } else {
                p1Vol
            }
            volumeText.text = "当前音量: %d%%".format((ratio * 100).toInt())
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onResume() { super.onResume(); updateUIState() }
    override fun onStop() { super.onStop(); saveSettings() }
    override fun onDestroy() { super.onDestroy(); if (isMonitoring) stopSpeedMonitoring() }
}
