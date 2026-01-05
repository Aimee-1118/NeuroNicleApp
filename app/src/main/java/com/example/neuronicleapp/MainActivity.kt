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

data class EegData(val timestamp: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI 요소
    private lateinit var findButton: Button
    private lateinit var startMeasurementButton: Button
    private lateinit var stopButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var dataTextView: TextView
    private lateinit var eegChart: LineChart
    private lateinit var subjectIdInput: EditText
    private lateinit var locationInput: EditText
    private lateinit var conditionInput: EditText
    private lateinit var fabHistory: FloatingActionButton

    // 블루투스 관련
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var dataReadingJob: Job? = null // 데이터를 읽는 백그라운드 작업

    // 데이터 처리 관련
    private lateinit var listAdapter: ArrayAdapter<String>
    private val deviceList = ArrayList<BluetoothDevice>()
    private var startTimeMillis: Long = 0L

    // [구조 변경] isMeasuring 플래그로만 녹화 여부를 결정합니다.
    private var isMeasuring = false

    private val eegDataBuffer = mutableListOf<EegData>()

    // 실험 정보 저장 변수
    private var subjectId: String = ""
    private var location: String = ""
    private var condition: String = ""

    private var currentCsvUriString: String? = null
    private var currentPngUriString: String? = null

    // [DC 오프셋 필터 변수]
    private var prevX_ch1: Float = 0f
    private var prevY_ch1: Float = 0f
    private var prevX_ch2: Float = 0f
    private var prevY_ch2: Float = 0f
    private val ALPHA = 0.95f // 필터 강도 (0.9 ~ 0.99)

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
        subjectIdInput = findViewById(R.id.subject_id_input)
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

        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = false
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
        xAxis.granularity = 1f

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
        eegChart.setVisibleXRangeMaximum(3f)

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
                // 측정 중일 때만 timestamp 계산 (아닐 땐 0초 혹은 현재 시간)
                val elapsedTime = if (isMeasuring) (data.timestamp - startTimeMillis) / 1000f else 0f

                // 그래프는 측정 중이 아니어도 데이터 확인용으로 계속 그려줍니다.
                // (만약 측정 중에만 그리고 싶다면 if (isMeasuring) 블록 안으로 넣으세요)

                // 그래프 X축 계속 흐르게 하기 위해 System time 사용 (옵션)
                // 여기서는 기존 로직 유지하되, 측정 시작 전에는 그래프가 겹쳐 보일 수 있으므로
                // 측정 시작 시 clearValues() 하는 로직을 활용합니다.

                lineData.addEntry(Entry(elapsedTime, data.channel1), 0)
                lineData.addEntry(Entry(elapsedTime, data.channel2), 1)

                lineData.notifyDataChanged()
                eegChart.notifyDataSetChanged()

                val maxEntries = 750
                if (set1.entryCount > maxEntries) {
                    set1.removeFirst()
                    set2.removeFirst()
                }

                eegChart.setVisibleXRangeMaximum(3f)
                eegChart.moveViewToX(lineData.xMax)
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

    private fun connectToDevice(device: BluetoothDevice) {
        // 기존 연결 및 작업 정리
        try { bluetoothSocket?.close() } catch (e: Exception) {}
        dataReadingJob?.cancel()

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "${device.name} 연결 중...", Toast.LENGTH_SHORT).show()
            }
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "연결 성공!", Toast.LENGTH_LONG).show()
                    startMeasurementButton.isEnabled = true
                }

                // [구조 변경] 연결 성공 시 바로 데이터 수신 시작 (측정 버튼과 무관하게 계속 읽음)
                dataReadingJob = parseAndReadData(bluetoothSocket!!)

            } catch (e: IOException) {
                Log.e(TAG, "Socket connection failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "연결 실패.", Toast.LENGTH_LONG).show()
                }
                try { bluetoothSocket?.close() } catch (e2: IOException) {}
            }
        }
    }

    private fun startMeasurement() {
        // [구조 변경] 이미 연결되어 있고 데이터는 들어오고 있음. 여기서는 "녹화 시작" 플래그만 설정.
        isMeasuring = true
        startTimeMillis = System.currentTimeMillis() // 기준 시간 재설정

        // 버퍼 및 그래프 초기화
        eegDataBuffer.clear()
        eegChart.data?.clearValues()
        eegChart.notifyDataSetChanged()
        eegChart.invalidate()

        // 필터 초기화
        prevX_ch1 = 0f; prevY_ch1 = 0f
        prevX_ch2 = 0f; prevY_ch2 = 0f

        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = true

        Toast.makeText(this, "측정을 시작합니다.", Toast.LENGTH_SHORT).show()
        // [삭제됨] parseAndReadData() 호출 제거 (이미 connectToDevice에서 실행 중)
    }

    private fun stopMeasurementAndAskToSave() {
        // [구조 변경] 데이터 수신 루프(Job)를 끄지 않고, 녹화 플래그만 끔
        isMeasuring = false
        // dataReadingJob?.cancel() // <-- 이거 절대 하지 않음! (연결 끊기 전까지 계속 읽어야 함)

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

    private fun parseAndReadData(socket: BluetoothSocket): Job {
        return lifecycleScope.launch(Dispatchers.IO) {
            val inputStream: InputStream = socket.inputStream
            val dataBuffer = mutableListOf<Byte>()
            val readBuffer = ByteArray(1024)

            // isActive 체크: 연결이 끊어지거나 앱이 종료될 때 루프 종료
            while (isActive && socket.isConnected) {
                try {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead > 0) {
                        dataBuffer.addAll(readBuffer.take(bytesRead))

                        // 패킷 싱크 찾기 (0xFF, 0xFE)
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

                                // [중요] 측정 중(isMeasuring)일 때만 버퍼에 저장
                                if (isMeasuring) {
                                    synchronized(eegDataBuffer) {
                                        eegDataBuffer.add(eegData)
                                    }
                                }

                                // 그래프 업데이트는 측정 여부 상관없이 보여줌 (연결 상태 확인용)
                                // 단, 측정 시작 전에는 startTimeMillis가 0이므로 X축이 이상할 수 있으니
                                // 필요하면 if (isMeasuring) 조건을 걸어도 됨.
                                withContext(Dispatchers.Main) {
                                    dataTextView.text = "CH1: %.2f µV\nCH2: %.2f µV".format(eegData.channel1, eegData.channel2)
                                    // 그래프에 그리기
                                    if (isMeasuring || startMeasurementButton.isEnabled) {
                                        addChartEntry(eegData)
                                    }
                                }
                                dataBuffer.subList(0, PACKET_LENGTH).clear()
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Connection lost in loop", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "장치 연결이 끊어졌습니다.", Toast.LENGTH_LONG).show()
                        startMeasurementButton.isEnabled = false
                        stopButton.isEnabled = false
                        isMeasuring = false
                    }
                    break
                }
            }
        }
    }

    // High-Pass Filter가 적용된 파싱 로직
    private fun parsePacket(packet: ByteArray): EegData {
        val ch1Raw = ((packet[9].toInt() shl 8) or (packet[8].toInt() and 0xFF))
        val ch2Raw = ((packet[11].toInt() shl 8) or (packet[10].toInt() and 0xFF))

        val conversionFactor = 0.01199f
        val rawCh1uV = ch1Raw * conversionFactor
        val rawCh2uV = ch2Raw * conversionFactor

        // DC 오프셋 제거 (High-Pass Filter)
        val filteredCh1 = ALPHA * (prevY_ch1 + rawCh1uV - prevX_ch1)
        val filteredCh2 = ALPHA * (prevY_ch2 + rawCh2uV - prevX_ch2)

        prevX_ch1 = rawCh1uV; prevY_ch1 = filteredCh1
        prevX_ch2 = rawCh2uV; prevY_ch2 = filteredCh2

        return EegData(System.currentTimeMillis(), filteredCh1, filteredCh2)
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
        dataTextView.text = "CH1: 0.00 µV\nCH2: 0.00 µV"
    }

    private fun saveDataToFile() {
        if (eegDataBuffer.isEmpty()) {
            Toast.makeText(this, "저장할 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val stimCode = when (condition.lowercase(Locale.getDefault()).trim()) {
            "church" -> 1
            "market" -> 2
            else -> 0
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "EEG_${subjectId}_${condition}_$timestamp.csv"
        val content = StringBuilder()

        content.append("# Subject ID: $subjectId\n")
        content.append("# Location: $location\n")
        content.append("# Condition: $condition (Code: $stimCode)\n")
        content.append("# Start Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(startTimeMillis))}\n")
        content.append("time,Fp1,Fp2,stim\n")

        synchronized(eegDataBuffer) {
            eegDataBuffer.forEach { data ->
                val elapsedTimeSeconds = (data.timestamp - startTimeMillis) / 1000f
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
                    stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    OutputStreamWriter(stream, "UTF-8").apply {
                        write(content.toString())
                        flush()
                    }
                }
                currentCsvUriString = fileUri.toString()
                Toast.makeText(this, "CSV 저장 완료: $fileName", Toast.LENGTH_SHORT).show()
                if (currentPngUriString != null) {
                    saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, currentCsvUriString, currentPngUriString))
                    currentCsvUriString = null; currentPngUriString = null
                }
            }
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
                    currentCsvUriString = null; currentPngUriString = null
                }
            }
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

    private fun saveHistoryItem(item: HistoryItem) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val historyJson = prefs.getString(HISTORY_KEY, null)
        val historyList: MutableList<HistoryItem> = if (historyJson != null) {
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            try { gson.fromJson(historyJson, type) } catch (e: Exception) { mutableListOf() }
        } else { mutableListOf() }
        historyList.add(0, item)
        prefs.edit().putString(HISTORY_KEY, gson.toJson(historyList)).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        dataReadingJob?.cancel() // 앱 종료 시에만 읽기 작업 취소
        try { bluetoothSocket?.close() } catch (e: IOException) {}
    }
}