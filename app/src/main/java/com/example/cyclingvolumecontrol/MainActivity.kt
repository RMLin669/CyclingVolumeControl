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
    private var currentDevicePrefix = "NONE_" // 初始设为 NONE 以强制第一次加载
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

        // 3. 基础组件初始化（仅设置适配器，不执行读取逻辑）
        setupSamplingSpinner()
        setupSpeedPickers()
        setupVolumeSlider()

        // 4. 开关监听（只在用户点击时手动保存）
        autoStartSwitch.setOnClickListener {
            saveSettings()
            if (autoStartSwitch.isChecked && !isMonitoring) {
                startSpeedMonitoring()
            }
        }

        // 5. 初始检测设备：此时会强制执行一次 loadSettingsForCurrentDevice
        updateDeviceType()

        // 6. 注册音频设备实时监听
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateDeviceType() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateDeviceType() }
            }, null)
        }

        // 7. 基础交互逻辑
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnStart.setOnClickListener {
            if (isMonitoring) stopSpeedMonitoring() else startSpeedMonitoring()
        }

        updateUIState()

        val btnShowPrivacy = findViewById<Button>(R.id.btnShowPrivacy)
        btnShowPrivacy.setOnClickListener {
            val privacyMessage = """
                🔒 隐私说明：
                本 App 获取的位置信息仅用于实时计算速度以调节音量，绝不会进行任何形式的上传或共享。
                
                💡 使用建议：
                1. 在多任务界面【锁定】本 App。
                2. 将本 App 的省电策略修改为【无限制】。
                3. 关闭系统的全局省电模式。
            """.trimIndent()

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("隐私说明与建议")
                .setMessage(privacyMessage)
                .setPositiveButton("我知道了", null)
                .show()
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

        // 检测到设备发生物理切换（前缀变化）或者初始化加载
        if (newPrefix != currentDevicePrefix) {
            val wasMonitoring = isMonitoring

            // 情况 A: 如果当前正在控制音量，无论新设备如何，先停止旧的控制
            if (wasMonitoring) {
                stopSpeedMonitoring()
            }

            // 切换前缀
            currentDevicePrefix = newPrefix

            // 加载新设备的配置
            // 该函数内部会判断新配置的 shouldAutoStart：
            //   - 如果新配置 autoStart 为 true -> 它会自动调用 startSpeedMonitoring()
            //   - 如果新配置 autoStart 为 false -> 它不会调用启动，由于上面已停止，最终状态就是“停止并切换配置”
            loadSettingsForCurrentDevice()

            // UI 提示
            if (wasMonitoring) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val willAutoStart = prefs.getBoolean(currentDevicePrefix + KEY_AUTO_START, false)
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

    private fun saveSettings() {
        // 如果前缀还没初始化，不执行保存
        if (currentDevicePrefix == "NONE_") return

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

        // 1-4 步：加载参数
        npMinTen.value = prefs.getInt(currentDevicePrefix + KEY_MIN_TEN, 0)
        npMinUnit.value = prefs.getInt(currentDevicePrefix + KEY_MIN_UNIT, 5)
        npMaxTen.value = prefs.getInt(currentDevicePrefix + KEY_MAX_TEN, 3)
        npMaxUnit.value = prefs.getInt(currentDevicePrefix + KEY_MAX_UNIT, 0)

        val savedMin = prefs.getFloat(currentDevicePrefix + KEY_VOL_MIN, 20f)
        val savedMax = prefs.getFloat(currentDevicePrefix + KEY_VOL_MAX, 100f)
        volumeRangeSlider.values = listOf(savedMin, savedMax)
        volumeRangeText.text = "${savedMin.toInt()}% - ${savedMax.toInt()}%"

        val savedSamplingPos = prefs.getInt(currentDevicePrefix + KEY_SAMPLING, 0)
        samplingSpinner.setSelection(savedSamplingPos)
        maxHistorySize = when (savedSamplingPos) { 0 -> 1; 1 -> 3; 2 -> 5; else -> 1 }
        speedHistory.clear()

        minSpeed = (npMinTen.value * 10 + npMinUnit.value).toFloat()
        maxSpeed = (npMaxTen.value * 10 + npMaxUnit.value).toFloat()
        minVolRatio = savedMin / 100f
        maxVolRatio = savedMax / 100f

        // 5. 加载自动启动状态
        val shouldAutoStart = prefs.getBoolean(currentDevicePrefix + KEY_AUTO_START, false)
        autoStartSwitch.isChecked = shouldAutoStart

        // 6. 核心判定：自动开启
        val hasFineLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (shouldAutoStart && !isMonitoring && hasFineLocation) {
            window.decorView.post {
                if (!isMonitoring) startSpeedMonitoring()
            }
        }

        // 7. 同步参数
        if (isMonitoring) syncToService()

        // 8. 恢复锁定功能：根据当前监控状态刷新控件可用性
        updateUIState()
    }

    private fun syncToService() {
        SpeedMonitorService.minSpeed = minSpeed
        SpeedMonitorService.maxSpeed = maxSpeed
        SpeedMonitorService.minVolRatio = minVolRatio
        SpeedMonitorService.maxVolRatio = maxVolRatio
        SpeedMonitorService.maxHistorySize = maxHistorySize
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
                // 核心修复：只有非初始化触发的（用户手动选的）才保存
                if (v != null) {
                    saveSettings()
                    if (isMonitoring) syncToService()
                }
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
        volumeRangeSlider.addOnChangeListener { slider, _, fromUser ->
            val v = slider.values
            volumeRangeText.text = "${v[0].toInt()}% - ${v[1].toInt()}%"
            minVolRatio = v[0] / 100f
            maxVolRatio = v[1] / 100f
            if (fromUser) saveSettings()
            if (isMonitoring) syncToService()
        }
    }

    private fun updateUIState() {
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // 权限按钮逻辑
        if (hasFine) {
            btnPermissionFine.text = "✅"; btnPermissionFine.isEnabled = false
            btnPermissionFine.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            if (!isMonitoring) btnStart.isEnabled = true
        } else {
            btnPermissionFine.text = "授权"; btnPermissionFine.isEnabled = true
            btnPermissionFine.setOnClickListener { requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
            btnStart.isEnabled = false
        }

        // 恢复监控中禁止修改的功能
        val canEdit = !isMonitoring

        npMinTen.isEnabled = canEdit
        npMinUnit.isEnabled = canEdit
        npMaxTen.isEnabled = canEdit
        npMaxUnit.isEnabled = canEdit
        volumeRangeSlider.isEnabled = canEdit
        samplingSpinner.isEnabled = canEdit
        // 自动开启开关通常允许在监控中修改（决定下次是否自启），若也要禁止可加上：
        // autoStartSwitch.isEnabled = canEdit
    }

    private fun startSpeedMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            syncToService()
            val intent = Intent(this, SpeedMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, uiLocationListener)
            isMonitoring = true;
            btnStart.text = "⏹ 停止控制"
            updateUIState() // 立即锁定控件
        }
    }

    private fun stopSpeedMonitoring() {
        stopService(Intent(this, SpeedMonitorService::class.java))
        locationManager.removeUpdates(uiLocationListener)
        isMonitoring = false;
        btnStart.text = "开始控制音量"
        speedText.text = "当前速度: 0.0 km/h";
        volumeText.text = "当前音量: --%"
        updateUIState() // 立即解锁控件
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