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

// 2026.01.09 수정 버전: 뉴로니클 E2 팩터 적용 및 그래프 기능 제거
data class EegData(val timestamp: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI 요소
    private lateinit var findButton: Button
    private lateinit var startMeasurementButton: Button
    private lateinit var stopButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var dataTextView: TextView
    private lateinit var subjectIdInput: EditText
    private lateinit var locationInput: EditText
    private lateinit var conditionInput: EditText
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

    // 실험 정보 저장 변수
    private var subjectId: String = ""
    private var location: String = ""
    private var condition: String = ""
    private var currentCsvUriString: String? = null

    // [DC 오프셋 필터 변수]
    private var prevX_ch1: Float = 0f
    private var prevY_ch1: Float = 0f
    private var prevX_ch2: Float = 0f
    private var prevY_ch2: Float = 0f
    private val ALPHA = 0.95f

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "MainActivity"
        private const val PACKET_LENGTH = 36
        private const val PREFS_NAME = "MeasurementHistoryPrefs"
        private const val HISTORY_KEY = "measurementHistory"

        // NeuroNicle E2 전용 정밀도 (Scale Factor)
        private const val EEG_SCALE_FACTOR_E2 = 0.2235f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupUI()
    }

    private fun setupUI() {
        findButton = findViewById(R.id.find_button)
        startMeasurementButton = findViewById(R.id.start_measurement_button)
        stopButton = findViewById(R.id.stop_button)
        devicesListView = findViewById(R.id.devices_listview)
        dataTextView = findViewById(R.id.data_textview)
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

    private fun connectToDevice(device: BluetoothDevice) {
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
        isMeasuring = true
        startTimeMillis = System.currentTimeMillis()
        eegDataBuffer.clear()

        prevX_ch1 = 0f; prevY_ch1 = 0f
        prevX_ch2 = 0f; prevY_ch2 = 0f

        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = true

        Toast.makeText(this, "측정을 시작합니다.", Toast.LENGTH_SHORT).show()
    }

    private fun stopMeasurementAndAskToSave() {
        isMeasuring = false
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

            while (isActive && socket.isConnected) {
                try {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead > 0) {
                        dataBuffer.addAll(readBuffer.take(bytesRead).toList())

                        var syncIndex = -1
                        for (i in 0 until dataBuffer.size - 1) {
                            if (dataBuffer[i] == 0xFF.toByte() && dataBuffer[i + 1] == 0xFE.toByte()) {
                                syncIndex = i
                                break
                            }
                        }

                        if (syncIndex != -1) {
                            if (syncIndex > 0) {
                                for (i in 0 until syncIndex) dataBuffer.removeAt(0)
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
                                }
                                for (i in 0 until PACKET_LENGTH) dataBuffer.removeAt(0)
                            }
                        }
                    }
                } catch (e: IOException) {
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

    private fun parsePacket(packet: ByteArray): EegData {
        // Little Endian 처리 및 Short 캐스팅으로 부호 비트 유지
        val ch1Raw = ((packet[9].toInt() shl 8) or (packet[8].toInt() and 0xFF)).toShort()
        val ch2Raw = ((packet[11].toInt() shl 8) or (packet[10].toInt() and 0xFF)).toShort()

        val rawCh1uV = ch1Raw * EEG_SCALE_FACTOR_E2
        val rawCh2uV = ch2Raw * EEG_SCALE_FACTOR_E2

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
    }

    private fun resetUI() {
        eegDataBuffer.clear()
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

                // 히스토리 저장 (이미지가 없으므로 이미지 경로엔 null 전달)
                saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, currentCsvUriString, null))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save CSV file", e)
            Toast.makeText(this, "Failed to save CSV.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findPairedDevices() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 1); return
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
        dataReadingJob?.cancel()
        try { bluetoothSocket?.close() } catch (e: IOException) {}
    }
}