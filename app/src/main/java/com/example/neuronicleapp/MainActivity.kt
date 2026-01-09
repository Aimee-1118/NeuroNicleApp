package com.example.neuronicleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
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

// 2026.01.09 최종 수정: 공식 문서 규격 적용 및 파일명 시간 추가
data class EegData(val sampleIndex: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var findButton: Button
    private lateinit var startMeasurementButton: Button
    private lateinit var stopButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var dataTextView: TextView
    private lateinit var subjectIdInput: EditText
    private lateinit var conditionInput: EditText

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var dataReadingJob: Job? = null

    private lateinit var listAdapter: ArrayAdapter<String>
    private val deviceList = ArrayList<BluetoothDevice>()
    private var isMeasuring = false
    private val eegDataBuffer = mutableListOf<EegData>()
    private var totalSampleCounter = 0L

    private var filterCh1 = ProfessionalEegFilter()
    private var filterCh2 = ProfessionalEegFilter()

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PACKET_LENGTH = 18
        private const val SAMPLING_RATE = 250.0
        private const val CENTER_VALUE = 16384     // 공식 문서 6p: 16,384 가 아날로그 0V
        private const val SCALE_FACTOR_UV = 0.02404f // 공식 문서 6p: 24.04nV/digit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        setupUI()
    }

    private fun setupUI() {
        findButton = findViewById(R.id.find_button); startMeasurementButton = findViewById(R.id.start_measurement_button)
        stopButton = findViewById(R.id.stop_button); devicesListView = findViewById(R.id.devices_listview)
        dataTextView = findViewById(R.id.data_textview); subjectIdInput = findViewById(R.id.subject_id_input)
        conditionInput = findViewById(R.id.condition_input)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = listAdapter

        findButton.setOnClickListener { findPairedDevices() }
        devicesListView.setOnItemClickListener { _, _, position, _ -> connectToDevice(deviceList[position]) }
        startMeasurementButton.setOnClickListener { startMeasurement() }
        stopButton.setOnClickListener { if(isMeasuring) stopMeasurementAndAskToSave() }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        dataReadingJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "연결 성공", Toast.LENGTH_SHORT).show()
                    startMeasurementButton.isEnabled = true
                }
                dataReadingJob = parseAndReadData(bluetoothSocket!!)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "연결 실패", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun startMeasurement() {
        isMeasuring = true; totalSampleCounter = 0L; eegDataBuffer.clear()
        filterCh1 = ProfessionalEegFilter(); filterCh2 = ProfessionalEegFilter()
        startMeasurementButton.isEnabled = false; stopButton.isEnabled = true
        Toast.makeText(this, "측정 중...", Toast.LENGTH_SHORT).show()
    }

    private fun stopMeasurementAndAskToSave() {
        isMeasuring = false; startMeasurementButton.isEnabled = true; stopButton.isEnabled = false
        AlertDialog.Builder(this).setTitle("측정 완료").setMessage("데이터를 저장하시겠습니까?")
            .setPositiveButton("예") { _, _ -> saveData() }
            .setNegativeButton("아니오") { _, _ -> eegDataBuffer.clear() }.show()
    }

    private fun parseAndReadData(socket: BluetoothSocket): Job {
        return lifecycleScope.launch(Dispatchers.IO) {
            val inputStream: InputStream = socket.inputStream
            val dataBuffer = mutableListOf<Byte>()
            val readBuffer = ByteArray(1024)
            var uiThrottle = 0

            while (isActive && socket.isConnected) {
                try {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead > 0) {
                        for (i in 0 until bytesRead) dataBuffer.add(readBuffer[i])
                        while (dataBuffer.size >= PACKET_LENGTH) {
                            if (dataBuffer[0] == 0xFF.toByte() && dataBuffer[1] == 0xFE.toByte()) {
                                val packet = dataBuffer.take(PACKET_LENGTH).toByteArray()
                                val eegData = parsePacket(packet)
                                if (isMeasuring) synchronized(eegDataBuffer) { eegDataBuffer.add(eegData) }

                                uiThrottle++
                                if (uiThrottle >= 25) {
                                    withContext(Dispatchers.Main) {
                                        dataTextView.text = "Fp1: %.2f µV\nFp2: %.2f µV".format(eegData.channel1, eegData.channel2)
                                    }
                                    uiThrottle = 0
                                }
                                for (i in 0 until PACKET_LENGTH) dataBuffer.removeAt(0)
                            } else { dataBuffer.removeAt(0) }
                        }
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun parsePacket(packet: ByteArray): EegData {
        // [공식 규격 적용] 상위 7비트(index 8) x 256 + 하위 8비트(index 9)
        val ch1High = packet[8].toInt() and 0x7F
        val ch1Low = packet[9].toInt() and 0xFF
        val ch1Value = (ch1High * 256) + ch1Low

        val ch2High = packet[10].toInt() and 0x7F
        val ch2Low = packet[11].toInt() and 0xFF
        val ch2Value = (ch2High * 256) + ch2Low

        // 공식 수식: (측정값 - 중심값) * 24.04nV
        val raw1 = (ch1Value - CENTER_VALUE) * SCALE_FACTOR_UV
        val raw2 = (ch2Value - CENTER_VALUE) * SCALE_FACTOR_UV

        val filtered1 = filterCh1.process(raw1)
        val filtered2 = filterCh2.process(raw2)

        return EegData(totalSampleCounter++, filtered1, filtered2)
    }

    private fun saveData() {
        val sid = subjectIdInput.text.toString().ifBlank { "Unknown" }
        val cond = conditionInput.text.toString().ifBlank { "Test" }

        // [수정] 파일명에 날짜와 시간 포함
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "EEG_${sid}_${cond}_${timestamp}.csv"

        lifecycleScope.launch(Dispatchers.IO) {
            val csv = StringBuilder().append("# Subject: $sid\n# Condition: $cond\n# Date: $timestamp\ntime,Fp1,Fp2\n")
            synchronized(eegDataBuffer) {
                eegDataBuffer.forEach {
                    csv.append("%.3f,%.3f,%.3f\n".format(Locale.US, it.sampleIndex/SAMPLING_RATE, it.channel1, it.channel2))
                }
            }
            saveToStorage(fileName, csv.toString())
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "저장 완료: $fileName", Toast.LENGTH_SHORT).show()
                eegDataBuffer.clear()
            }
        }
    }

    private fun saveToStorage(name: String, content: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NeuroNicleApp")
                }
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            uri?.let { contentResolver.openOutputStream(it)?.use { os ->
                os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM
                os.write(content.toByteArray(Charsets.UTF_8))
            }}
        } catch (e: Exception) { Log.e("Save", "Fail", e) }
    }

    private fun findPairedDevices() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1); return
        }
        listAdapter.clear(); deviceList.clear()
        bluetoothAdapter.bondedDevices?.forEach { device -> deviceList.add(device); listAdapter.add("${device.name}\n${device.address}") }
    }

    class ProfessionalEegFilter {
        private val na0 = 0.9414f; private val na1 = -1.1067f; private val na2 = 0.9414f
        private val nb1 = -1.1067f; private val nb2 = 0.8828f
        private var nx1 = 0f; private var nx2 = 0f; private var ny1 = 0f; private var ny2 = 0f

        private val ba0 = 0.1425f; private val ba1 = 0f; private val ba2 = -0.1425f
        private val bb1 = -1.6111f; private val bb2 = 0.7150f
        private var bx1 = 0f; private var bx2 = 0f; private var by1 = 0f; private var by2 = 0f

        fun process(input: Float): Float {
            val nOut = na0 * input + na1 * nx1 + na2 * nx2 - nb1 * ny1 - nb2 * ny2
            nx2 = nx1; nx1 = input; ny2 = ny1; ny1 = nOut
            val bOut = ba0 * nOut + ba1 * bx1 + ba2 * bx2 - bb1 * by1 - bb2 * by2
            bx2 = bx1; bx1 = nOut; by2 = by1; by1 = bOut
            return bOut
        }
    }
}