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

// 2026.01.09 최종 수정: 뉴로니클 E2 하드웨어 완벽 대응 버전
data class EegData(val timestamp: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var findButton: Button
    private lateinit var startMeasurementButton: Button
    private lateinit var stopButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var dataTextView: TextView
    private lateinit var subjectIdInput: EditText
    private lateinit var locationInput: EditText
    private lateinit var conditionInput: EditText
    private lateinit var fabHistory: FloatingActionButton

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var dataReadingJob: Job? = null

    private lateinit var listAdapter: ArrayAdapter<String>
    private val deviceList = ArrayList<BluetoothDevice>()
    private var startTimeMillis: Long = 0L
    private var isMeasuring = false
    private val eegDataBuffer = mutableListOf<EegData>()

    private var subjectId: String = ""
    private var location: String = ""
    private var condition: String = ""
    private var currentCsvUriString: String? = null

    // DC 오프셋 필터 (High-Pass Filter)
    private var prevX_ch1: Float = 0f
    private var prevY_ch1: Float = 0f
    private var prevX_ch2: Float = 0f
    private var prevY_ch2: Float = 0f
    private val ALPHA = 0.98f // 250Hz 샘플링에 최적화된 필터 계수

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "MainActivity"

        // [중요] 뉴로니클 E2 하드웨어 스펙
        private const val PACKET_LENGTH = 18     // E2 패킷은 18바이트입니다.
        private const val EEG_SCALE_FACTOR = 0.01199f // E2 공식 정밀도 (uV/LSB)

        private const val PREFS_NAME = "MeasurementHistoryPrefs"
        private const val HISTORY_KEY = "measurementHistory"
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

        stopButton.setOnClickListener { if(isMeasuring) stopMeasurementAndAskToSave() }
        fabHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try { bluetoothSocket?.close() } catch (e: Exception) {}
        dataReadingJob?.cancel()

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "${device.name} 연결 중...", Toast.LENGTH_SHORT).show() }
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "연결 성공!", Toast.LENGTH_LONG).show()
                    startMeasurementButton.isEnabled = true
                }
                dataReadingJob = parseAndReadData(bluetoothSocket!!)
            } catch (e: IOException) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "연결 실패.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startMeasurement() {
        isMeasuring = true
        startTimeMillis = System.currentTimeMillis()
        eegDataBuffer.clear()
        prevX_ch1 = 0f; prevY_ch1 = 0f; prevX_ch2 = 0f; prevY_ch2 = 0f
        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = true
        Toast.makeText(this, "측정을 시작합니다. (250Hz 모드)", Toast.LENGTH_SHORT).show()
    }

    private fun stopMeasurementAndAskToSave() {
        isMeasuring = false
        startMeasurementButton.isEnabled = true
        stopButton.isEnabled = false
        AlertDialog.Builder(this)
            .setTitle("측정 완료")
            .setMessage("데이터를 저장하시겠습니까?")
            .setPositiveButton("예") { _, _ -> saveData(); resetUI() }
            .setNegativeButton("아니오") { _, _ -> resetUI() }
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
                        for (i in 0 until bytesRead) dataBuffer.add(readBuffer[i])

                        // 효율적인 패킷 처리 (18바이트 기준)
                        while (dataBuffer.size >= PACKET_LENGTH) {
                            if (dataBuffer[0] == 0xFF.toByte() && dataBuffer[1] == 0xFE.toByte()) {
                                if (dataBuffer.size < PACKET_LENGTH) break

                                val packet = ByteArray(PACKET_LENGTH)
                                for (i in 0 until PACKET_LENGTH) packet[i] = dataBuffer.removeAt(0)

                                val eegData = parsePacket(packet)

                                if (isMeasuring) {
                                    synchronized(eegDataBuffer) { eegDataBuffer.add(eegData) }
                                }

                                withContext(Dispatchers.Main) {
                                    dataTextView.text = "CH1: %.2f µV\nCH2: %.2f µV".format(eegData.channel1, eegData.channel2)
                                }
                            } else {
                                dataBuffer.removeAt(0) // 싱크가 아니면 한 바이트씩 버림
                            }
                        }
                    }
                } catch (e: IOException) { break }
            }
        }
    }

    private fun parsePacket(packet: ByteArray): EegData {
        // E2 패킷 구조: [8,9]가 CH1, [10,11]이 CH2 (Little Endian)
        val ch1Raw = ((packet[9].toInt() shl 8) or (packet[8].toInt() and 0xFF)).toShort()
        val ch2Raw = ((packet[11].toInt() shl 8) or (packet[10].toInt() and 0xFF)).toShort()

        val rawCh1uV = ch1Raw * EEG_SCALE_FACTOR
        val rawCh2uV = ch2Raw * EEG_SCALE_FACTOR

        // High-Pass Filter 적용 (DC Offset 제거)
        val filteredCh1 = ALPHA * (prevY_ch1 + rawCh1uV - prevX_ch1)
        val filteredCh2 = ALPHA * (prevY_ch2 + rawCh2uV - prevX_ch2)

        prevX_ch1 = rawCh1uV; prevY_ch1 = filteredCh1
        prevX_ch2 = rawCh2uV; prevY_ch2 = filteredCh2

        return EegData(System.currentTimeMillis(), filteredCh1, filteredCh2)
    }

    private fun saveData() {
        subjectId = subjectIdInput.text.toString().ifBlank { "Unknown" }
        location = locationInput.text.toString()
        condition = conditionInput.text.toString()
        saveDataToFile()
    }

    private fun resetUI() {
        eegDataBuffer.clear()
        dataTextView.text = "CH1: 0.00 µV\nCH2: 0.00 µV"
    }

    private fun saveDataToFile() {
        if (eegDataBuffer.isEmpty()) return
        val stimCode = when (condition.lowercase().trim()) { "church" -> 1; "market" -> 2; else -> 0 }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "EEG_${subjectId}_${condition}_$timestamp.csv"
        val content = StringBuilder()

        content.append("# Subject ID: $subjectId\n# Location: $location\n# Condition: $condition\n")
        content.append("time,Fp1,Fp2,stim\n")

        synchronized(eegDataBuffer) {
            eegDataBuffer.forEach { data ->
                val elapsed = (data.timestamp - startTimeMillis) / 1000f
                content.append("${String.format(Locale.US, "%.3f", elapsed)},${data.channel1},${data.channel2},$stimCode\n")
            }
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NeuroNicleApp")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            uri?.let { fileUri ->
                contentResolver.openOutputStream(fileUri)?.use { it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())); OutputStreamWriter(it, "UTF-8").apply { write(content.toString()); flush() } }
                saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, fileUri.toString(), null))
                Toast.makeText(this, "저장 완료: $fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { Log.e(TAG, "Save failed", e) }
    }

    private fun findPairedDevices() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1); return
        }
        listAdapter.clear(); deviceList.clear()
        bluetoothAdapter.bondedDevices?.forEach { device -> deviceList.add(device); listAdapter.add("${device.name}\n${device.address}") }
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

    override fun onDestroy() { super.onDestroy(); dataReadingJob?.cancel(); try { bluetoothSocket?.close() } catch (e: Exception) {} }
}