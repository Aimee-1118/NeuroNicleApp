package com.example.neuronicleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
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

// Data class (with timestamp)
data class EegData(val timestamp: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var findButton: Button
    private lateinit var saveButton: Button
    private lateinit var stopButton: Button
    private lateinit var devicesListView: ListView
    private lateinit var dataTextView: TextView
    private lateinit var eegChart: LineChart
    private lateinit var stateTextView: TextView

    // Bluetooth
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var dataReadingJob: Job? = null

    // Data Handling
    private lateinit var listAdapter: ArrayAdapter<String>
    private val deviceList = ArrayList<BluetoothDevice>()
    private var dataCount = 0
    private var isSaving = false
    private val eegDataBuffer = mutableListOf<EegData>()

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "MainActivity"
        private const val PACKET_LENGTH = 36
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
        saveButton = findViewById(R.id.save_button)
        stopButton = findViewById(R.id.stop_button)
        devicesListView = findViewById(R.id.devices_listview)
        dataTextView = findViewById(R.id.data_textview)
        stateTextView = findViewById(R.id.state_textview)
        eegChart = findViewById(R.id.eeg_chart)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = listAdapter

        findButton.setOnClickListener { findPairedDevices() }
        devicesListView.setOnItemClickListener { _, _, position, _ -> connectToDevice(deviceList[position]) }
        saveButton.setOnClickListener { toggleSaving() }

        stopButton.setOnClickListener {
            dataReadingJob?.cancel()
            Toast.makeText(this, "Measurement stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupChart() {
        eegChart.description.isEnabled = false
        eegChart.legend.isEnabled = true
        eegChart.xAxis.setDrawLabels(false)
        eegChart.axisLeft.axisMaximum = 200f
        eegChart.axisLeft.axisMinimum = -200f
        eegChart.axisRight.isEnabled = false

        val ch1DataSet = createSet("Channel 1", Color.RED)
        val ch2DataSet = createSet("Channel 2", Color.BLUE)

        eegChart.data = LineData(ch1DataSet, ch2DataSet)
    }

    private fun addChartEntry(data: EegData) {
        val lineData = eegChart.data
        if (lineData != null) {
            val set1 = lineData.getDataSetByIndex(0) as LineDataSet
            val set2 = lineData.getDataSetByIndex(1) as LineDataSet

            set1.addEntry(Entry(dataCount.toFloat(), data.channel1))
            set2.addEntry(Entry(dataCount.toFloat(), data.channel2))
            dataCount++

            lineData.notifyDataChanged()
            eegChart.notifyDataSetChanged()
            eegChart.setVisibleXRangeMaximum(200f)
            eegChart.moveViewToX(lineData.entryCount.toFloat())
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

    private fun toggleSaving() {
        isSaving = !isSaving
        if (isSaving) {
            Toast.makeText(this, "Started saving data.", Toast.LENGTH_SHORT).show()
            saveButton.text = "Stop Saving"
            eegDataBuffer.clear()
        } else {
            Toast.makeText(this, "Stopped saving data.", Toast.LENGTH_SHORT).show()
            saveButton.text = "Save Data"
            saveDataToFile()
            saveChartAsImage()
        }
    }

    private fun saveDataToFile() {
        if (eegDataBuffer.isEmpty()) {
            Toast.makeText(this, "No data to save.", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "EEG_DATA_$timestamp.csv"
        val content = StringBuilder("Timestamp,Channel1,Channel2\n")
        eegDataBuffer.forEach { data -> content.append("${data.timestamp},${data.channel1},${data.channel2}\n") }
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NeuroNicleApp")
                }
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { stream -> OutputStreamWriter(stream).use { writer -> writer.write(content.toString()) } }
                Toast.makeText(this, "CSV saved.", Toast.LENGTH_SHORT).show()
            } ?: throw IOException("Failed to create URI")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save CSV file", e)
            Toast.makeText(this, "Failed to save CSV.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveChartAsImage() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "EEG_CHART_$timestamp.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NeuroNicleApp")
            }
        }
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { stream ->
                    eegChart.getChartBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                Toast.makeText(this, "Chart image saved.", Toast.LENGTH_SHORT).show()
            } ?: throw IOException("Failed to create URI")
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
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show() }
            try {
                bluetoothSocket?.close()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_LONG).show()
                    dataReadingJob?.cancel()
                    dataReadingJob = bluetoothSocket?.let { parseAndReadData(it) }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Socket connection failed", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Connection failed.", Toast.LENGTH_LONG).show() }
                try { bluetoothSocket?.close() } catch (closeException: IOException) { /* Ignore */ }
            }
        }
    }

    private fun parseAndReadData(socket: BluetoothSocket): Job {
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
                            if (dataBuffer[i] == 0xFF.toByte() && dataBuffer[i + 1] == 0xFE.toByte()) { syncIndex = i; break }
                        }
                        if (syncIndex != -1) {
                            if (syncIndex > 0) dataBuffer.subList(0, syncIndex).clear()
                            while (dataBuffer.size >= PACKET_LENGTH) {
                                val packet = dataBuffer.take(PACKET_LENGTH).toByteArray()
                                val eegData = parsePacket(packet)

                                if (isSaving) {
                                    eegDataBuffer.add(eegData)
                                }

                                withContext(Dispatchers.Main) {
                                    dataTextView.text = "CH1: %.2f µV\nCH2: %.2f µV".format(eegData.channel1, eegData.channel2)
                                    addChartEntry(eegData)
                                    detectState(eegData)
                                }
                                dataBuffer.subList(0, PACKET_LENGTH).clear()
                            }
                        }
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Connection lost.", Toast.LENGTH_LONG).show() }
                    break
                }
            }
        }
    }

    private fun parsePacket(packet: ByteArray): EegData {
        val ch1Raw = (packet[9].toInt() and 0xFF shl 8) or (packet[8].toInt() and 0xFF)
        val ch2Raw = (packet[11].toInt() and 0xFF shl 8) or (packet[10].toInt() and 0xFF)
        val conversionFactor = 0.40283203125f
        val ch1uV = (ch1Raw - 8192) * conversionFactor
        val ch2uV = (ch2Raw - 8192) * conversionFactor
        return EegData(System.currentTimeMillis(), ch1uV, ch2uV)
    }

    private fun detectState(data: EegData) {
        val averageAmplitude = (kotlin.math.abs(data.channel1) + kotlin.math.abs(data.channel2)) / 2
        val state = if (averageAmplitude < 20) {
            "State: Relaxed"
        } else {
            "State: Active"
        }
        stateTextView.text = state
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