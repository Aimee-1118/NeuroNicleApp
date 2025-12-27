package com.example.neuronicleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

// 2025.10.18.13.55

// Data class (with timestamp)
data class EegData(val timestamp: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI 요소
    private lateinit var findButton: Button
    private lateinit var startMeasurementButton: Button // ⭐ 측정 시작 버튼
    private lateinit var stopButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var dataTextView: TextView
    private lateinit var eegChart: LineChart
    private lateinit var subjectIdInput: EditText // ⭐ 피험자 ID 입력 필드
    private lateinit var locationInput: EditText // ⭐ 장소 입력 필드
    private lateinit var conditionInput: EditText // ⭐ 조건 입력 필드
    private lateinit var fabHistory: FloatingActionButton

    // 블루투스 관련
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var dataReadingJob: Job? = null

    // 데이터 처리 관련
    private lateinit var listAdapter: ArrayAdapter<String>
    private val deviceList = ArrayList<BluetoothDevice>()
    private var startTimeMillis: Long = 0L
    private var isMeasuring = false
    private val eegDataBuffer = mutableListOf<EegData>()
    private var dataCountForGraph = 0 // 그래프 X축용 카운터

    // ⭐ 실험 정보 저장 변수
    private var subjectId: String = ""
    private var location: String = ""
    private var condition: String = ""

    private var currentCsvUriString: String? = null // 현재 저장된 CSV URI 저장
    private var currentPngUriString: String? = null // 현재 저장된 PNG URI 저장

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "MainActivity"
        private const val PACKET_LENGTH = 36

        private const val PREFS_NAME = "MeasurementHistoryPrefs"
        private const val HISTORY_KEY = "measurementHistory"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupUI()
        setupChart()
    }

    private fun setupUI() {
        findButton = findViewById(R.id.find_button)
        startMeasurementButton = findViewById(R.id.start_measurement_button)
        stopButton = findViewById(R.id.stop_button)
        devicesListView = findViewById(R.id.devices_listview)
        dataTextView = findViewById(R.id.data_textview)
        eegChart = findViewById(R.id.eeg_chart)
        subjectIdInput = findViewById(R.id.subject_id_input) // ⭐ 입력 필드 연결
        locationInput = findViewById(R.id.location_input)
        conditionInput = findViewById(R.id.condition_input)
        fabHistory = findViewById(R.id.fab_history)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = listAdapter

        findButton.setOnClickListener { findPairedDevices() }
        devicesListView.setOnItemClickListener { _, _, position, _ -> connectToDevice(deviceList[position]) }

        startMeasurementButton.setOnClickListener {
            if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
                Toast.makeText(this, "먼저 장치에 연결해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startMeasurement()
        }

        stopButton.setOnClickListener {
            if(isMeasuring) {
                stopMeasurementAndAskToSave()
            }
        }

        fabHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        startMeasurementButton.isEnabled = false // 초기에는 측정 시작 버튼 비활성화
        stopButton.isEnabled = false // 초기에는 중지 버튼 비활성화
    }

    private fun setupChart() {
        eegChart.description.isEnabled = false
        eegChart.legend.isEnabled = true
        eegChart.legend.textColor = Color.BLACK

        val xAxis = eegChart.xAxis
        xAxis.textColor = Color.BLACK
        xAxis.setDrawGridLines(false)
        xAxis.setAvoidFirstLastClipping(true)
        xAxis.isEnabled = true
        xAxis.setDrawLabels(true)
        xAxis.granularity = 1f // 1초 간격으로 라벨 표시

        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val millis = TimeUnit.SECONDS.toMillis(value.toLong())
                val sdf = SimpleDateFormat("mm:ss", Locale.getDefault())
                return sdf.format(Date(millis))
            }
        }

        val leftAxis = eegChart.axisLeft
        leftAxis.textColor = Color.BLACK
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMaximum = 250f
        leftAxis.axisMinimum = -250f

        eegChart.axisRight.isEnabled = false

        eegChart.setTouchEnabled(true)
        eegChart.isDragEnabled = true
        eegChart.setScaleEnabled(true)
        eegChart.setDrawGridBackground(false)
        eegChart.setPinchZoom(true)
        eegChart.setBackgroundColor(Color.WHITE)

        eegChart.setVisibleXRangeMaximum(3f) // 한 번에 최대 3초 범위만 보여줌

        val set1 = createSet("Channel 1", Color.RED)
        val set2 = createSet("Channel 2", Color.BLUE)
        eegChart.data = LineData(set1, set2)
        eegChart.invalidate()
    }

    private fun addChartEntry(data: EegData) {
        val lineData = eegChart.data
        if (lineData != null) {
            val set1 = lineData.getDataSetByIndex(0)
            val set2 = lineData.getDataSetByIndex(1)

            if (set1 != null && set2 != null) {
                val elapsedTimeSeconds = (data.timestamp - startTimeMillis) / 1000f

                lineData.addEntry(Entry(elapsedTimeSeconds, data.channel1), 0)
                lineData.addEntry(Entry(elapsedTimeSeconds, data.channel2), 1)

                lineData.notifyDataChanged()
                eegChart.notifyDataSetChanged()

                // 데이터가 너무 많아지는 것을 방지하여 성능 유지 (약 3초 분량, 250Hz 기준)
                val maxEntries = 750
                if (set1.entryCount > maxEntries) {
                    set1.removeFirst() // 가장 오래된 데이터 제거
                    set2.removeFirst()
                }

                eegChart.setVisibleXRangeMaximum(3f)
                eegChart.moveViewToX(lineData.xMax) // 가장 최신 데이터로 스크롤
            }
        }
    }

    private fun createSet(label: String, color: Int): LineDataSet {
        val set = LineDataSet(null, label)
        set.color = color
        set.setDrawCircles(false)
        set.lineWidth = 1.5f
        set.setDrawValues(false)
        return set
    }

    private fun startMeasurement() {
        isMeasuring = true
        eegDataBuffer.clear()

        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = true

        Toast.makeText(this, "측정을 시작합니다.", Toast.LENGTH_SHORT).show()

        dataReadingJob?.cancel()
        dataReadingJob = parseAndReadData(bluetoothSocket!!)
    }

    private fun stopMeasurementAndAskToSave() {
        isMeasuring = false
        dataReadingJob?.cancel()

        startMeasurementButton.isEnabled = true
        stopButton.isEnabled = false

        AlertDialog.Builder(this)
            .setTitle("측정 완료")
            .setMessage("측정된 데이터를 저장하시겠습니까?")
            .setPositiveButton("예") { _, _ ->
                saveData()
                resetUI()
            }
            .setNegativeButton("아니오") { _, _ ->
                resetUI()
                Toast.makeText(this, "저장이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveData() {
        subjectId = subjectIdInput.text.toString()
        location = locationInput.text.toString()
        condition = conditionInput.text.toString()

        if (subjectId.isBlank()) {
            Toast.makeText(this, "피험자 ID를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        saveDataToFile()
        saveChartAsImage()
    }

    private fun resetUI() {
        eegDataBuffer.clear()
        eegChart.data?.clearValues()
        eegChart.xAxis.resetAxisMinimum()
        eegChart.xAxis.resetAxisMaximum()
        eegChart.notifyDataSetChanged()
        eegChart.invalidate()
        startTimeMillis = 0L
        dataCountForGraph = 0
        dataTextView.text = "CH1: 0.00 µV\nCH2: 0.00 µV"
    }

    private fun saveDataToFile() {
        if (eegDataBuffer.isEmpty()) {
            Toast.makeText(this, "저장할 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // ⭐ 1. 파이썬 분석 호환: 조건(Condition)을 숫자 코드로 변환
        // church -> 1, market -> 2 (대소문자 무시)
        val stimCode = when (condition.lowercase(Locale.getDefault()).trim()) {
            "church" -> 1
            "market" -> 2
            else -> 0
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        // 파일명에 condition 추가 (관리 용이성)
        val fileName = "EEG_${subjectId}_${condition}_$timestamp.csv"
        val content = StringBuilder()

        content.append("# Subject ID: $subjectId\n")
        content.append("# Location: $location\n")
        content.append("# Condition: $condition (Code: $stimCode)\n")
        content.append("# Start Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(startTimeMillis))}\n")

        // ⭐ 2. 파이썬 분석 호환: 헤더 수정 (m1_load.py 호환)
        // 기존: ElapsedSeconds,Channel1,Channel2
        // 변경: time,Fp1,Fp2,stim
        content.append("time,Fp1,Fp2,stim\n")

        synchronized(eegDataBuffer) {
            eegDataBuffer.forEach { data ->
                val elapsedTimeSeconds = (data.timestamp - startTimeMillis) / 1000f
                // ⭐ 3. 파이썬 분석 호환: 마지막 열에 stimCode 추가
                content.append("${String.format(Locale.US, "%.3f", elapsedTimeSeconds)},${data.channel1},${data.channel2},$stimCode\n")
            }
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NeuroNicleApp")
                }
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            uri?.let { fileUri ->
                contentResolver.openOutputStream(fileUri)?.use { stream ->
                    stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM
                    OutputStreamWriter(stream, "UTF-8").apply {
                        write(content.toString())
                        flush()
                    }
                }
                currentCsvUriString = fileUri.toString()
                Toast.makeText(this, "CSV 저장 완료: $fileName", Toast.LENGTH_SHORT).show()

                if (currentPngUriString != null) {
                    saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, currentCsvUriString, currentPngUriString))
                    currentCsvUriString = null
                    currentPngUriString = null
                }

            } ?: throw IOException("URI 생성 실패")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save CSV file", e)
            Toast.makeText(this, "Failed to save CSV.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveChartAsImage() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "EEG_CHART_${subjectId}_$timestamp.png"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NeuroNicleApp")
            }
        }

        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { imageUri ->
                contentResolver.openOutputStream(imageUri)?.use { stream ->
                    eegChart.getChartBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                currentPngUriString = imageUri.toString()
                Toast.makeText(this, "그래프 이미지 저장 완료", Toast.LENGTH_SHORT).show()

                if (currentCsvUriString != null) {
                    saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, currentCsvUriString, currentPngUriString))
                    currentCsvUriString = null
                    currentPngUriString = null
                }

            } ?: throw IOException("URI 생성 실패")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chart image", e)
            Toast.makeText(this, "Failed to save chart.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun findPairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1); return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), 1); return
            }
        }
        listAdapter.clear(); deviceList.clear()
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            listAdapter.add("No paired devices found.")
        } else {
            pairedDevices.forEach { device -> deviceList.add(device); listAdapter.add("${device.name}\n${device.address}") }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
            }
            try {
                bluetoothSocket?.close()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_LONG).show()
                    startMeasurementButton.isEnabled = true
                }
            } catch (e: IOException) {
                Log.e(TAG, "Socket connection failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection failed.", Toast.LENGTH_LONG).show()
                }
                try {
                    bluetoothSocket?.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "Failed to close socket after connection error", closeException)
                }
            }
        }
    }

    private fun parseAndReadData(socket: BluetoothSocket): Job {
        startTimeMillis = System.currentTimeMillis()
        return lifecycleScope.launch(Dispatchers.IO) {
            val inputStream: InputStream = socket.inputStream
            val dataBuffer = mutableListOf<Byte>()
            val readBuffer = ByteArray(1024)

            while (isActive) {
                try {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead > 0) {
                        dataBuffer.addAll(readBuffer.take(bytesRead))

                        var syncIndex = -1
                        for (i in 0 until dataBuffer.size - 1) {
                            if (dataBuffer[i] == 0xFF.toByte() && dataBuffer[i + 1] == 0xFE.toByte()) {
                                syncIndex = i
                                break
                            }
                        }

                        if (syncIndex != -1) {
                            if (syncIndex > 0) {
                                dataBuffer.subList(0, syncIndex).clear()
                            }

                            while (dataBuffer.size >= PACKET_LENGTH) {
                                val packet = dataBuffer.take(PACKET_LENGTH).toByteArray()
                                val eegData = parsePacket(packet)

                                if (isMeasuring) {
                                    synchronized(eegDataBuffer) {
                                        eegDataBuffer.add(eegData)
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    dataTextView.text = "CH1: %.2f µV\nCH2: %.2f µV".format(eegData.channel1, eegData.channel2)
                                    addChartEntry(eegData)
                                }
                                dataBuffer.subList(0, PACKET_LENGTH).clear()
                            }
                        }
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Connection lost.", Toast.LENGTH_LONG).show()
                        startMeasurementButton.isEnabled = true
                        stopButton.isEnabled = false
                    }
                    break
                }
            }
        }
    }
    private fun saveHistoryItem(item: HistoryItem) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()

        val historyJson = prefs.getString(HISTORY_KEY, null)
        val historyList: MutableList<HistoryItem> = if (historyJson != null) {
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            try {
                gson.fromJson(historyJson, type)
            } catch (e: Exception) {
                Log.e(TAG, "기존 기록 JSON 파싱 오류", e)
                mutableListOf()
            }
        } else {
            mutableListOf()
        }

        historyList.add(0, item)

        val updatedHistoryJson = gson.toJson(historyList)

        prefs.edit().putString(HISTORY_KEY, updatedHistoryJson).apply()
        Log.d(TAG, "측정 기록 저장 완료: ${historyList.size}개 항목")
    }

    private fun parsePacket(packet: ByteArray): EegData {
        val ch1Raw = ((packet[9].toInt() shl 8) or (packet[8].toInt() and 0xFF))
        val ch2Raw = ((packet[11].toInt() shl 8) or (packet[10].toInt() and 0xFF))

       val conversionFactor = 0.01199f

        val ch1uV = ch1Raw * conversionFactor
        val ch2uV = ch2Raw * conversionFactor

        return EegData(System.currentTimeMillis(), ch1uV, ch2uV)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close socket on destroy", e)
        }
    }
}