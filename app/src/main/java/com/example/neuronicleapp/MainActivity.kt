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

data class EegData(val sampleIndex: Long, val channel1: Float, val channel2: Float)

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
    private var isMeasuring = false
    private val eegDataBuffer = mutableListOf<EegData>()
    private var totalSampleCounter = 0L // CSV 저장용 샘플 카운터

    // 필터 변수 (ALPHA를 0.95로 조정하여 초기 안착 속도 개선)
    private var prevX_ch1 = 0f; private var prevY_ch1 = 0f
    private var prevX_ch2 = 0f; private var prevY_ch2 = 0f
    private val ALPHA = 0.95f

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PACKET_LENGTH = 18
        private const val EEG_SCALE_FACTOR = 0.2235f // NeuroNicle E2 표준값
        private const val SAMPLING_RATE = 250.0 // 250Hz
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
        devicesListView.setOnItemClickListener { _, _, pos, _ -> connectToDevice(deviceList[pos]) }
        startMeasurementButton.setOnClickListener { startMeasurement() }
        stopButton.setOnClickListener { if(isMeasuring) stopMeasurementAndAskToSave() }
        fabHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        startMeasurementButton.isEnabled = false; stopButton.isEnabled = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        dataReadingJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "연결 성공!", Toast.LENGTH_SHORT).show()
                    startMeasurementButton.isEnabled = true
                }
                dataReadingJob = parseAndReadData(bluetoothSocket!!)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "연결 실패", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun startMeasurement() {
        isMeasuring = true
        totalSampleCounter = 0
        eegDataBuffer.clear()
        // 필터 초기화
        prevX_ch1 = 0f; prevY_ch1 = 0f; prevX_ch2 = 0f; prevY_ch2 = 0f
        startMeasurementButton.isEnabled = false
        stopButton.isEnabled = true
        Toast.makeText(this, "측정 시작 (필터 안정화에 2~3초 소요)", Toast.LENGTH_SHORT).show()
    }

    private fun stopMeasurementAndAskToSave() {
        isMeasuring = false
        startMeasurementButton.isEnabled = true
        stopButton.isEnabled = false
        AlertDialog.Builder(this)
            .setTitle("측정 완료")
            .setMessage("저장하시겠습니까?")
            .setPositiveButton("예") { _, _ -> saveData() }
            .setNegativeButton("아니오") { _, _ -> eegDataBuffer.clear() }
            .show()
    }

    private fun parseAndReadData(socket: BluetoothSocket): Job {
        return lifecycleScope.launch(Dispatchers.IO) {
            val inputStream: InputStream = socket.inputStream
            val dataBuffer = mutableListOf<Byte>()
            val readBuffer = ByteArray(1024)
            var uiUpdateCounter = 0

            while (isActive && socket.isConnected) {
                try {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead > 0) {
                        for (i in 0 until bytesRead) dataBuffer.add(readBuffer[i])

                        while (dataBuffer.size >= PACKET_LENGTH) {
                            if (dataBuffer[0] == 0xFF.toByte() && dataBuffer[1] == 0xFE.toByte()) {
                                val packet = dataBuffer.take(PACKET_LENGTH).toByteArray()
                                val eegData = parsePacket(packet)

                                if (isMeasuring) {
                                    synchronized(eegDataBuffer) { eegDataBuffer.add(eegData) }
                                }

                                // UI 업데이트 최적화 (25샘플마다 1번 = 약 10Hz)
                                uiUpdateCounter++
                                if (uiUpdateCounter >= 25) {
                                    withContext(Dispatchers.Main) {
                                        dataTextView.text = "CH1: %.1f µV\nCH2: %.1f µV".format(eegData.channel1, eegData.channel2)
                                    }
                                    uiUpdateCounter = 0
                                }
                                dataBuffer.subList(0, PACKET_LENGTH).clear()
                            } else {
                                dataBuffer.removeAt(0)
                            }
                        }
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun parsePacket(packet: ByteArray): EegData {
        // E2: CH1[8,9], CH2[10,11] Little Endian
        val ch1Raw = ((packet[9].toInt() shl 8) or (packet[8].toInt() and 0xFF)).toShort()
        val ch2Raw = ((packet[11].toInt() shl 8) or (packet[10].toInt() and 0xFF)).toShort()

        val raw1 = ch1Raw * EEG_SCALE_FACTOR
        val raw2 = ch2Raw * EEG_SCALE_FACTOR

        // High-Pass Filter (DC 제거)
        val f1 = ALPHA * (prevY_ch1 + raw1 - prevX_ch1)
        val f2 = ALPHA * (prevY_ch2 + raw2 - prevX_ch2)

        prevX_ch1 = raw1; prevY_ch1 = f1
        prevX_ch2 = raw2; prevY_ch2 = f2

        return EegData(totalSampleCounter++, f1, f2)
    }

    private fun saveData() {
        val sid = subjectIdInput.text.toString().ifBlank { "Unknown" }
        val cond = conditionInput.text.toString().ifBlank { "Test" }
        val loc = locationInput.text.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "EEG_${sid}_${cond}_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}.csv"
            val csv = StringBuilder()
            csv.append("# Subject: $sid\n# Location: $loc\n# Condition: $cond\n")
            csv.append("time,Fp1,Fp2,stim\n")

            val stimCode = when(cond.lowercase()) { "church" -> 1; "market" -> 2; else -> 0 }

            synchronized(eegDataBuffer) {
                eegDataBuffer.forEach {
                    // 시간 계산: 샘플 인덱스 / 250Hz (매우 정확함)
                    val time = it.sampleIndex / SAMPLING_RATE
                    csv.append("%.3f,%.3f,%.3f,%d\n".format(Locale.US, time, it.channel1, it.channel2, stimCode))
                }
            }

            saveToStorage(fileName, csv.toString())
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "저장 완료", Toast.LENGTH_SHORT).show()
                eegDataBuffer.clear()
            }
        }
    }

    private fun saveToStorage(name: String, content: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NeuroNicleApp")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) { Log.e("Save", "Fail", e) }
    }

    private fun findPairedDevices() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1); return
        }
        listAdapter.clear(); deviceList.clear()
        bluetoothAdapter.bondedDevices?.forEach { device ->
            deviceList.add(device); listAdapter.add("${device.name}\n${device.address}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataReadingJob?.cancel()
        try { bluetoothSocket?.close() } catch (e: Exception) {}
    }
}