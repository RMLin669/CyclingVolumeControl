package com.example.cyclingvolumecontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

    private lateinit var btnPermissionFine: Button
    private lateinit var btnStart: Button
    private lateinit var speedText: TextView
    private lateinit var volumeText: TextView
    private lateinit var samplingSpinner: Spinner

    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager

    private val speedHistory = mutableListOf<Float>()
    private var maxHistorySize = 1

    private var isMonitoring = false

    private var minSpeed = 5f
    private var maxSpeed = 30f
    private var minVolRatio = 0.2f
    private var maxVolRatio = 1.0f

    private lateinit var volumeRangeText: TextView
    private lateinit var volumeRangeSlider: com.google.android.material.slider.RangeSlider

    private lateinit var npMinTen: NumberPicker
    private lateinit var npMinUnit: NumberPicker
    private lateinit var npMaxTen: NumberPicker
    private lateinit var npMaxUnit: NumberPicker

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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

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

        setupSamplingSpinner()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnStart.setOnClickListener {
            if (isMonitoring) stopSpeedMonitoring() else startSpeedMonitoring()
        }

        updateUIState()
        setupSpeedPickers()
        setupVolumeSlider()
    }

    private fun setupSamplingSpinner() {
        val options = arrayOf("1s (实时)", "3s (平滑)", "5s (极稳)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        samplingSpinner.adapter = adapter

        // --- 读取持久化数据 ---
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPos = prefs.getInt(KEY_SAMPLING, 0)
        samplingSpinner.setSelection(savedPos)

        samplingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                maxHistorySize = when (position) {
                    0 -> 1
                    1 -> 3
                    2 -> 5
                    else -> 1
                }
                speedHistory.clear()
                // --- 保存当前选择 ---
                saveSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateUIState() {
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine) {
            btnPermissionFine.text = "✅"
            btnPermissionFine.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            btnPermissionFine.elevation = 0f
            btnPermissionFine.isEnabled = false
            if (!isMonitoring) {
                btnStart.isEnabled = true
                btnStart.text = "开始控制音量"
            }
        } else {
            btnPermissionFine.text = "授权"
            btnPermissionFine.backgroundTintList = null
            btnPermissionFine.elevation = 4f
            btnPermissionFine.isEnabled = true
            btnPermissionFine.setOnClickListener {
                requestLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
            btnStart.isEnabled = false
        }
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        samplingSpinner.isEnabled = enabled
        npMinTen.isEnabled = enabled
        npMinUnit.isEnabled = enabled
        npMaxTen.isEnabled = enabled
        npMaxUnit.isEnabled = enabled
        volumeRangeSlider.isEnabled = enabled
    }

    private fun startSpeedMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            SpeedMonitorService.minSpeed = minSpeed
            SpeedMonitorService.maxSpeed = maxSpeed
            SpeedMonitorService.minVolRatio = minVolRatio
            SpeedMonitorService.maxVolRatio = maxVolRatio
            SpeedMonitorService.maxHistorySize = maxHistorySize

            val intent = Intent(this, SpeedMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, uiLocationListener)

            isMonitoring = true
            btnStart.text = "⏹ 停止控制音量"
            setSettingsEnabled(false)

            Toast.makeText(this, "音量控制已启动 (后台运行中)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSpeedMonitoring() {
        val intent = Intent(this, SpeedMonitorService::class.java)
        stopService(intent)

        locationManager.removeUpdates(uiLocationListener)
        speedText.text = "当前速度: 0.0 km/h"
        volumeText.text = "当前音量: --%"

        isMonitoring = false
        btnStart.text = "开始控制音量"
        setSettingsEnabled(true)

        Toast.makeText(this, "控制已停止", Toast.LENGTH_SHORT).show()
    }

    private fun setupSpeedPickers() {
        val configureNP = { np: NumberPicker ->
            np.minValue = 0
            np.maxValue = 9
            np.wrapSelectorWheel = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                np.selectionDividerHeight = 0
            }
        }

        // 应用基本配置
        listOf(npMinTen, npMinUnit, npMaxTen, npMaxUnit).forEach { configureNP(it) }

        // --- 从 SharedPreferences 读取上次保存的速度 ---
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        npMinTen.value = prefs.getInt(KEY_MIN_TEN, 0)
        npMinUnit.value = prefs.getInt(KEY_MIN_UNIT, 5)
        npMaxTen.value = prefs.getInt(KEY_MAX_TEN, 3)
        npMaxUnit.value = prefs.getInt(KEY_MAX_UNIT, 0)

        // 初始化内存中的 minSpeed 和 maxSpeed 变量
        minSpeed = (npMinTen.value * 10 + npMinUnit.value).toFloat()
        maxSpeed = (npMaxTen.value * 10 + npMaxUnit.value).toFloat()

        // 定义平滑滚动动画函数
        val scrollPickerWithAnimation = { picker: NumberPicker, increment: Boolean ->
            try {
                val method = picker.javaClass.getDeclaredMethod("changeValueByOne", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(picker, increment)
            } catch (e: Exception) {
                if (increment) picker.value++ else picker.value--
            }
        }

        // 定义进位逻辑
        val handleCarry = { _: NumberPicker, tenPicker: NumberPicker, oldVal: Int, newVal: Int ->
            if (oldVal == 9 && newVal == 0) {
                // 进位
                if (tenPicker.value < tenPicker.maxValue || tenPicker.wrapSelectorWheel) {
                    scrollPickerWithAnimation(tenPicker, true)
                }
            } else if (oldVal == 0 && newVal == 9) {
                // 借位
                if (tenPicker.value > tenPicker.minValue || tenPicker.wrapSelectorWheel) {
                    scrollPickerWithAnimation(tenPicker, false)
                }
            }
        }

        // 定义统一的数值更新与保存逻辑
        val updateSpeeds = {
            minSpeed = (npMinTen.value * 10 + npMinUnit.value).toFloat()
            maxSpeed = (npMaxTen.value * 10 + npMaxUnit.value).toFloat()
            saveSettings()
        }

        // 设置监听器：个位变动触发进位和更新，十位变动仅触发更新
        npMinUnit.setOnValueChangedListener { _, oldVal, newVal ->
            handleCarry(npMinUnit, npMinTen, oldVal, newVal)
            updateSpeeds()
        }
        npMinTen.setOnValueChangedListener { _, _, _ -> updateSpeeds() }

        npMaxUnit.setOnValueChangedListener { _, oldVal, newVal ->
            handleCarry(npMaxUnit, npMaxTen, oldVal, newVal)
            updateSpeeds()
        }
        npMaxTen.setOnValueChangedListener { _, _, _ -> updateSpeeds() }
    }

    private fun setupVolumeSlider() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMin = prefs.getFloat(KEY_VOL_MIN, 20f)
        val savedMax = prefs.getFloat(KEY_VOL_MAX, 100f)
        volumeRangeSlider.values = listOf(savedMin, savedMax)

        // 初始化数值变量
        minVolRatio = savedMin / 100f
        maxVolRatio = savedMax / 100f
        volumeRangeText.text = "${savedMin.toInt()}% - ${savedMax.toInt()}%"

        volumeRangeSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val minInt = values[0].toInt()
            val maxInt = values[1].toInt()
            volumeRangeText.text = "$minInt% - $maxInt%"
            minVolRatio = minInt / 100f
            maxVolRatio = maxInt / 100f
            saveSettings() // 实时保存
        }
    }

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

    private val uiLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val currentSpeed = location.speed * 3.6f
            val targetVolRatio = calculateVolumeRatio(currentSpeed)
            speedText.text = "当前速度: %.1f km/h".format(currentSpeed)
            volumeText.text = "当前音量: %d%%".format((targetVolRatio * 100).toInt())
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_MIN_TEN, npMinTen.value)
            putInt(KEY_MIN_UNIT, npMinUnit.value)
            putInt(KEY_MAX_TEN, npMaxTen.value)
            putInt(KEY_MAX_UNIT, npMaxUnit.value)
            putFloat(KEY_VOL_MIN, volumeRangeSlider.values[0])
            putFloat(KEY_VOL_MAX, volumeRangeSlider.values[1])
            putInt(KEY_SAMPLING, samplingSpinner.selectedItemPosition)
            apply()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveSettings()
        if (isMonitoring) stopSpeedMonitoring()
    }

    override fun onStop() {
        super.onStop()
        saveSettings()
    }
}