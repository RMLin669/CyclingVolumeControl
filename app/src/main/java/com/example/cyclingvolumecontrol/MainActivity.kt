package com.example.cyclingvolumecontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    // 状态变量
    private var currentDevicePrefix = "PHONE_"
    private var isMonitoring = false
    private var maxHistorySize = 1
    private val speedHistory = mutableListOf<Float>()

    private lateinit var autoStartSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private val KEY_AUTO_START = "auto_start"

    // 实时数值
    private var minSpeed = 5f
    private var maxSpeed = 30f
    private var minVolRatio = 0.2f
    private var maxVolRatio = 1.0f

    // UI 控件
    private lateinit var deviceTypeText: TextView
    private lateinit var btnPermissionFine: Button
    private lateinit var btnStart: Button
    private lateinit var speedText: TextView
    private lateinit var volumeText: TextView
    private lateinit var samplingSpinner: Spinner
    private lateinit var volumeRangeText: TextView
    private lateinit var volumeRangeSlider: com.google.android.material.slider.RangeSlider
    private lateinit var npMinTen: NumberPicker
    private lateinit var npMinUnit: NumberPicker
    private lateinit var npMaxTen: NumberPicker
    private lateinit var npMaxUnit: NumberPicker

    // 服务相关
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager

    // 常量
    private val PREFS_NAME = "CyclingSettings"
    private val KEY_MIN_TEN = "min_ten"
    private val KEY_MIN_UNIT = "min_unit"
    private val KEY_MAX_TEN = "max_ten"
    private val KEY_MAX_UNIT = "max_unit"
    private val KEY_VOL_MIN = "vol_min"
    private val KEY_VOL_MAX = "vol_max"
    private val KEY_SAMPLING = "sampling_pos"

    private val requestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> updateUIState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 1. 初始化系统服务
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 2. 绑定 UI
        btnPermissionFine = findViewById(R.id.btnPermissionFine)
        btnStart = findViewById(R.id.btnStart)
        speedText = findViewById(R.id.speedText)
        volumeText = findViewById(R.id.volumeText)
        samplingSpinner = findViewById(R.id.samplingSpinner)
        volumeRangeText = findViewById(R.id.volumeRangeText)
        volumeRangeSlider = findViewById(R.id.volumeRangeSlider)
        npMinTen = findViewById(R.id.npMinTen)
        npMinUnit = findViewById(R.id.npMinUnit)
        npMaxTen = findViewById(R.id.npMaxTen)
        npMaxUnit = findViewById(R.id.npMaxUnit)
        deviceTypeText = findViewById(R.id.deviceTypeText)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)

        // 3. 注册音频设备监听
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateDeviceType() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateDeviceType() }
            }, null)
        }

        // 4. 初始化控件设置
        setupSamplingSpinner()
        setupSpeedPickers()
        setupVolumeSlider()

        // 5. 设置开关监听
        autoStartSwitch.setOnCheckedChangeListener { _, _ -> saveSettings() }

        // 6. 初始检测设备类型（会触发配置加载和可能的自动启动）
        updateDeviceType()

        // 7. 基础交互
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnStart.setOnClickListener {
            if (isMonitoring) stopSpeedMonitoring() else startSpeedMonitoring()
        }

        updateUIState()
    }

    private fun updateDeviceType() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var isBluetooth = false
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                isBluetooth = true
                break
            }
        }
        val newPrefix = if (isBluetooth) "BT_" else "PHONE_"
        if (newPrefix != currentDevicePrefix) {
            currentDevicePrefix = newPrefix
            loadSettingsForCurrentDevice()
            Toast.makeText(this, "载入${if(isBluetooth)"蓝牙" else "外放"}配置", Toast.LENGTH_SHORT).show()
        }
        deviceTypeText.text = if (isBluetooth) "🎧 蓝牙模式" else "📱 外放模式"
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(currentDevicePrefix + KEY_MIN_TEN, npMinTen.value)
            putInt(currentDevicePrefix + KEY_MIN_UNIT, npMinUnit.value)
            putInt(currentDevicePrefix + KEY_MAX_TEN, npMaxTen.value)
            putInt(currentDevicePrefix + KEY_MAX_UNIT, npMaxUnit.value)
            putFloat(currentDevicePrefix + KEY_VOL_MIN, volumeRangeSlider.values[0])
            putFloat(currentDevicePrefix + KEY_VOL_MAX, volumeRangeSlider.values[1])
            putInt(currentDevicePrefix + KEY_SAMPLING, samplingSpinner.selectedItemPosition)
            putBoolean(currentDevicePrefix + KEY_AUTO_START, autoStartSwitch.isChecked)
            apply()
        }
    }

    private fun loadSettingsForCurrentDevice() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        npMinTen.value = prefs.getInt(currentDevicePrefix + KEY_MIN_TEN, 0)
        npMinUnit.value = prefs.getInt(currentDevicePrefix + KEY_MIN_UNIT, 5)
        npMaxTen.value = prefs.getInt(currentDevicePrefix + KEY_MAX_TEN, 3)
        npMaxUnit.value = prefs.getInt(currentDevicePrefix + KEY_MAX_UNIT, 0)

        val savedMin = prefs.getFloat(currentDevicePrefix + KEY_VOL_MIN, 20f)
        val savedMax = prefs.getFloat(currentDevicePrefix + KEY_VOL_MAX, 100f)
        volumeRangeSlider.values = listOf(savedMin, savedMax)
        volumeRangeText.text = "${savedMin.toInt()}% - ${savedMax.toInt()}%"
        samplingSpinner.setSelection(prefs.getInt(currentDevicePrefix + KEY_SAMPLING, 0))

        minSpeed = (npMinTen.value * 10 + npMinUnit.value).toFloat()
        maxSpeed = (npMaxTen.value * 10 + npMaxUnit.value).toFloat()
        minVolRatio = savedMin / 100f
        maxVolRatio = savedMax / 100f

        // 加载并应用开关状态
        val shouldAutoStart = prefs.getBoolean(currentDevicePrefix + KEY_AUTO_START, false)
        autoStartSwitch.isChecked = shouldAutoStart

        // 如果开启了自动控制且未在监控且按钮可用，则延时启动
        if (shouldAutoStart && !isMonitoring && btnStart.isEnabled) {
            btnStart.postDelayed({
                if (!isMonitoring && btnStart.isEnabled) startSpeedMonitoring()
            }, 500)
        }

        if (isMonitoring) syncToService()
    }

    private fun syncToService() {
        SpeedMonitorService.minSpeed = minSpeed
        SpeedMonitorService.maxSpeed = maxSpeed
        SpeedMonitorService.minVolRatio = minVolRatio
        SpeedMonitorService.maxVolRatio = maxVolRatio
    }

    private fun setupSamplingSpinner() {
        val options = arrayOf("1s (实时)", "3s (平滑)", "5s (极稳)")
        samplingSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        samplingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                maxHistorySize = when (pos) { 0 -> 1; 1 -> 3; 2 -> 5; else -> 1 }
                speedHistory.clear()
                saveSettings()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSpeedPickers() {
        val configureNP = { np: NumberPicker ->
            np.minValue = 0; np.maxValue = 9; np.wrapSelectorWheel = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) np.selectionDividerHeight = 0
        }
        listOf(npMinTen, npMinUnit, npMaxTen, npMaxUnit).forEach { configureNP(it) }

        val scrollAnim = { picker: NumberPicker, inc: Boolean ->
            try {
                val m = picker.javaClass.getDeclaredMethod("changeValueByOne", Boolean::class.javaPrimitiveType)
                m.isAccessible = true; m.invoke(picker, inc)
            } catch (e: Exception) { if (inc) picker.value++ else picker.value-- }
        }

        val handleCarry = { _: NumberPicker, ten: NumberPicker, old: Int, new: Int ->
            if (old == 9 && new == 0) scrollAnim(ten, true)
            else if (old == 0 && new == 9) scrollAnim(ten, false)
        }

        val update = {
            minSpeed = (npMinTen.value * 10 + npMinUnit.value).toFloat()
            maxSpeed = (npMaxTen.value * 10 + npMaxUnit.value).toFloat()
            saveSettings()
            if (isMonitoring) syncToService()
        }

        npMinUnit.setOnValueChangedListener { _, o, n -> handleCarry(npMinUnit, npMinTen, o, n); update() }
        npMinTen.setOnValueChangedListener { _, _, _ -> update() }
        npMaxUnit.setOnValueChangedListener { _, o, n -> handleCarry(npMaxUnit, npMaxTen, o, n); update() }
        npMaxTen.setOnValueChangedListener { _, _, _ -> update() }
    }

    private fun setupVolumeSlider() {
        volumeRangeSlider.addOnChangeListener { slider, _, _ ->
            val v = slider.values
            volumeRangeText.text = "${v[0].toInt()}% - ${v[1].toInt()}%"
            minVolRatio = v[0] / 100f
            maxVolRatio = v[1] / 100f
            saveSettings()
            if (isMonitoring) syncToService()
        }
    }

    private fun updateUIState() {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine) {
            btnPermissionFine.text = "✅"; btnPermissionFine.isEnabled = false
            btnPermissionFine.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            if (!isMonitoring) btnStart.isEnabled = true
        } else {
            btnPermissionFine.text = "授权"; btnPermissionFine.isEnabled = true
            btnPermissionFine.setOnClickListener { requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
            btnStart.isEnabled = false
        }
    }

    private fun startSpeedMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            syncToService()
            SpeedMonitorService.maxHistorySize = maxHistorySize
            val intent = Intent(this, SpeedMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, uiLocationListener)
            isMonitoring = true; btnStart.text = "⏹ 停止控制"
            Toast.makeText(this, "监控已启动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSpeedMonitoring() {
        stopService(Intent(this, SpeedMonitorService::class.java))
        locationManager.removeUpdates(uiLocationListener)
        isMonitoring = false; btnStart.text = "开始控制音量"
        speedText.text = "当前速度: 0.0 km/h"; volumeText.text = "当前音量: --%"
    }

    private val uiLocationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val s = l.speed * 3.6f
            speedText.text = "当前速度: %.1f km/h".format(s)
            val ratio = if (minSpeed < maxSpeed) {
                when { s <= minSpeed -> minVolRatio; s >= maxSpeed -> maxVolRatio; else -> minVolRatio + (maxVolRatio - minVolRatio) * (s - minSpeed) / (maxSpeed - minSpeed) }
            } else {
                when { s >= minSpeed -> minVolRatio; s <= maxSpeed -> maxVolRatio; else -> minVolRatio + (maxVolRatio - minVolRatio) * (s - minSpeed) / (maxSpeed - minSpeed) }
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