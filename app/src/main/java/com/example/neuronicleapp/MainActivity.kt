package com.example.neuronicleapp

import com.github.mikephil.charting.formatter.ValueFormatter

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
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
import java.util.concurrent.TimeUnit // 시간 변환 위해 추가

//2025.10.18.13.55

// Data class (with timestamp)
data class EegData(val timestamp: Long, val channel1: Float, val channel2: Float)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // UI 요소
    private lateinit var findButton: Button
    private lateinit var startMeasurementButton: Button // ⭐ 측정 시작 버튼
    private lateinit var saveButton: Button
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
    private var isSaving = false
    private val eegDataBuffer = mutableListOf<EegData>()
    private var dataCountForGraph = 0 // 그래프 X축용 카운터 (경과 시간과 별개로 사용될 수 있음)

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
        saveButton = findViewById(R.id.save_button)
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
        // ⭐ 측정 시작 버튼 클릭 리스너
        startMeasurementButton.setOnClickListener {
            if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
                Toast.makeText(this, "먼저 장치에 연결해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 입력된 정보 저장
            subjectId = subjectIdInput.text.toString()
            location = locationInput.text.toString()
            condition = conditionInput.text.toString()

            Toast.makeText(this, "측정을 시작합니다.", Toast.LENGTH_SHORT).show()
            // 데이터 수신 시작
            dataReadingJob?.cancel() // 혹시 이전 작업이 있다면 취소
            dataReadingJob = parseAndReadData(bluetoothSocket!!) // Non-null assertion 사용
        }

        saveButton.setOnClickListener { toggleSaving() }

        stopButton.setOnClickListener {
            dataReadingJob?.cancel()
            isSaving = false
            saveButton.text = "데이터 저장"
            eegDataBuffer.clear()
            eegChart.data?.clearValues()
            eegChart.xAxis.resetAxisMinimum()
            eegChart.xAxis.resetAxisMaximum()
            eegChart.notifyDataSetChanged()
            eegChart.invalidate()
            startTimeMillis = 0L
            dataCountForGraph = 0 // 그래프 카운터 리셋
            dataTextView.text = "CH1: 0.00 µV\nCH2: 0.00 µV"
            Toast.makeText(this, "측정을 중지하고 초기화했습니다.", Toast.LENGTH_SHORT).show()
        }

        fabHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupChart() {
        eegChart.description.isEnabled = false // 차트 설명 비활성화
        eegChart.legend.isEnabled = true // 범례(Legend) 활성화 (Channel 1, Channel 2 표시)
        eegChart.legend.textColor = Color.BLACK // 범례 글자색

        // --- X축 설정 ---
        val xAxis = eegChart.xAxis
        xAxis.textColor = Color.BLACK // X축 글자색
        xAxis.setDrawGridLines(false) // X축 배경 격자선 비활성화
        xAxis.setAvoidFirstLastClipping(true) // 첫/마지막 라벨 잘림 방지
        xAxis.isEnabled = true // X축 활성화
        xAxis.setDrawLabels(true) // X축 라벨 표시 활성화
        xAxis.granularity = 10f // 약 10초 간격으로 라벨 표시 (조절 가능)

        // X축 레이블 포맷터 설정 (경과 시간 초 -> mm:ss 형식)
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                // 초 단위를 밀리초로 변환하여 mm:ss 형식으로 포맷
                val millis = TimeUnit.SECONDS.toMillis(value.toLong())
                // SimpleDateFormat은 스레드에 안전하지 않으므로 지역 변수로 생성하는 것이 좋습니다.
                val sdf = SimpleDateFormat("mm:ss", Locale.getDefault())
                return sdf.format(Date(millis))
            }
        }

        // --- Y축 (왼쪽) 설정 ---
        val leftAxis = eegChart.axisLeft
        leftAxis.textColor = Color.BLACK // Y축 글자색
        leftAxis.setDrawGridLines(true) // Y축 배경 격자선 활성화
        // Y축 범위 설정 (PC 프로그램 스크린샷 기준)
        leftAxis.axisMaximum = 250f
        leftAxis.axisMinimum = -250f

        // --- Y축 (오른쪽) 비활성화 ---
        eegChart.axisRight.isEnabled = false

        // --- 기타 차트 설정 ---
        eegChart.setTouchEnabled(true) // 터치 상호작용 활성화
        eegChart.isDragEnabled = true // 드래그 활성화
        eegChart.setScaleEnabled(true) // 확대/축소 활성화
        eegChart.setDrawGridBackground(false) // 배경 격자 비활성화
        eegChart.setPinchZoom(true) // 두 손가락으로 확대/축소 활성화
        eegChart.setBackgroundColor(Color.WHITE) // 차트 배경색

        // --- 초기 데이터셋 추가 ---
        // 빈 데이터셋으로 초기화하여 NullPointerException 방지 및 스타일 적용
        val set1 = createSet("Channel 1", Color.RED) // PC 스크린샷 기준 색상 적용
        val set2 = createSet("Channel 2", Color.BLUE) // PC 스크린샷 기준 색상 적용 (스크린샷은 빨간색/흰색이지만 구분 위해 파란색 사용)
        eegChart.data = LineData(set1, set2) // 빈 LineData 객체 할당
        eegChart.invalidate() // 차트 초기 상태 그리기
    }

    // MainActivity.kt 파일 내부

    private fun addChartEntry(data: EegData) {
        // 차트의 LineData 객체를 가져옵니다. null일 수 있으므로 null 체크.
        val lineData = eegChart.data
        if (lineData != null) {
            // LineData에서 첫 번째 데이터셋(Channel 1)과 두 번째 데이터셋(Channel 2)을 가져옵니다.
            // 안전한 타입 캐스팅 (as? LineDataSet) 후 null 체크를 하거나, 아래처럼 직접 캐스팅 후 사용합니다.
            val set1 = lineData.getDataSetByIndex(0) as LineDataSet
            val set2 = lineData.getDataSetByIndex(1) as LineDataSet

            // X축 값으로 사용할 경과 시간(초)을 계산합니다.
            val elapsedTimeSeconds = (data.timestamp - startTimeMillis) / 1000f

            // 계산된 경과 시간과 해당 채널의 뇌파 값(uV)으로 Entry 객체를 생성하여 각 데이터셋에 추가합니다.
            // addEntry는 데이터셋 끝에 데이터를 추가합니다.
            lineData.addEntry(Entry(elapsedTimeSeconds, data.channel1), 0) // 0번 인덱스 = set1
            lineData.addEntry(Entry(elapsedTimeSeconds, data.channel2), 1) // 1번 인덱스 = set2

            // 그래프에 표시할 최대 데이터 포인트 개수를 정의합니다. (예: 1000개는 약 4초 분량)
            // 이 값을 조절하여 그래프에 표시되는 시간 범위를 변경할 수 있습니다. (예: 60초 * 250Hz = 15000)
            val maxVisibleEntries = 1000
            // 현재 데이터셋의 엔트리 개수가 최대 개수를 초과하는지 확인합니다.
            if (set1.entryCount > maxVisibleEntries) {
                // 초과하면 가장 오래된 엔트리(첫 번째 엔트리)를 제거합니다.
                set1.removeFirst()
                set2.removeFirst()
                // 참고: removeFirst()는 데이터셋 내부적으로 X축 최소값 등을 관리하므로,
                // 수동으로 lineData.xMin 등을 업데이트할 필요는 보통 없습니다.
            }

            // LineData 객체에 데이터가 변경되었음을 알립니다.
            lineData.notifyDataChanged()
            // LineChart 객체에 데이터셋이 변경되었음을 알립니다.
            eegChart.notifyDataSetChanged()

            // 차트 뷰를 가장 최근 데이터(가장 오른쪽)로 스크롤합니다.
            eegChart.moveViewToX(elapsedTimeSeconds)

            // 차트를 다시 그리도록 요청합니다. (데이터 변경 및 스크롤 적용)
            eegChart.invalidate()
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

    /**
     * '데이터 저장'/'저장 중지' 버튼 클릭 시 호출되는 함수.
     * isSaving 플래그를 토글하고, 상태에 따라 버퍼 초기화 또는 파일 저장 함수를 호출합니다.
     */
    private fun toggleSaving() {
        // isSaving 플래그의 값을 반전시킵니다 (true -> false, false -> true).
        isSaving = !isSaving
        if (isSaving) {
            // 저장을 시작하는 경우
            Toast.makeText(this, "Started saving data.", Toast.LENGTH_SHORT).show()
            saveButton.text = "Stop Saving" // 버튼 텍스트 변경
            eegDataBuffer.clear() // 새 저장을 위해 데이터 버퍼 초기화
        } else {
            // 저장을 중지하는 경우
            Toast.makeText(this, "Stopped saving data.", Toast.LENGTH_SHORT).show()
            saveButton.text = "Save Data" // 버튼 텍스트 원래대로 변경
            saveDataToFile() // 버퍼에 쌓인 데이터를 파일로 저장하는 함수 호출
            saveChartAsImage() // 그래프 이미지 저장 함수 호출
        }
    }

    /**
     * eegDataBuffer에 저장된 데이터를 CSV 파일로 저장하는 함수.
     * MediaStore API를 사용하여 공용 'Documents/NeuroNicleApp' 폴더에 저장합니다.
     */
    private fun saveDataToFile() {
        // 저장할 데이터가 버퍼에 없으면 사용자에게 알리고 함수 종료
        if (eegDataBuffer.isEmpty()) {
            Toast.makeText(this, "No data to save.", Toast.LENGTH_SHORT).show()
            return
        }

        // 파일 이름 생성을 위한 현재 시간 포맷 (예: "20251018_125200")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 최종 파일 이름 조합 (예: "EEG_DATA_Subject1_20251018_125200.csv")
        val fileName = "EEG_DATA_${subjectId}_$timestamp.csv"
        // CSV 파일 내용을 담을 StringBuilder 초기화
        val content = StringBuilder()

        // CSV 헤더 추가: 실험 정보 (주석 처리)
        content.append("# Subject ID: $subjectId\n")
        content.append("# Location: $location\n")
        content.append("# Condition: $condition\n")
        content.append("# Start Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(startTimeMillis))}\n")
        // CSV 데이터 열 이름 헤더
        content.append("ElapsedSeconds,Channel1,Channel2\n")

        // 데이터 버퍼에 접근 제어를 위해 synchronized 블록 사용
        synchronized(eegDataBuffer) {
            // 버퍼 내의 모든 EegData 객체에 대해 반복
            eegDataBuffer.forEach { data ->
                // 각 데이터의 경과 시간(초) 계산
                val elapsedTimeSeconds = (data.timestamp - startTimeMillis) / 1000f
                // CSV 형식으로 데이터 행 추가 (경과 시간은 소수점 3자리까지)
                content.append("${String.format(Locale.US, "%.3f", elapsedTimeSeconds)},${data.channel1},${data.channel2}\n")
            }
        }

        // 파일을 저장하기 위한 로직 (MediaStore API 사용)
        try {
            // 파일 정보를 담는 ContentValues 객체 생성
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) // 파일명 설정
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv") // 파일 타입 설정
                // 안드로이드 Q (API 29) 이상 버전에서는 저장될 상대 경로 지정
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NeuroNicleApp") // 공용 Documents 폴더 아래 NeuroNicleApp 폴더
                }
            }
            // MediaStore에 파일 생성 요청하고 결과 URI 받기
            // "external"은 외부 저장소(보통 내장 메모리)를 의미
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            uri?.let { fileUri -> // ⭐ URI를 fileUri 변수로 받음
                contentResolver.openOutputStream(fileUri)?.use { stream ->
                    OutputStreamWriter(stream).use { writer -> writer.write(content.toString()) }
                }
                currentCsvUriString = fileUri.toString() // ⭐ 성공 시 URI 저장
                Toast.makeText(this, "CSV 저장 완료", Toast.LENGTH_SHORT).show()

                // ⭐ CSV와 PNG 모두 저장된 후에 기록 저장 호출 (saveChartAsImage 내부에서 호출하도록 변경 가능)
                // if (currentPngUriString != null) {
                //     saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, currentCsvUriString, currentPngUriString))
                //     currentCsvUriString = null // 초기화
                //     currentPngUriString = null
                // }

            } ?: throw IOException("URI 생성 실패")
        } catch (e: Exception) { // 파일 저장 중 발생할 수 있는 모든 예외 처리
            Log.e(TAG, "Failed to save CSV file", e) // 에러 로그 기록
            // 사용자에게 저장 실패 메시지 표시
            Toast.makeText(this, "Failed to save CSV.", Toast.LENGTH_SHORT).show()
        }
    }
    /**
     * 현재 표시된 그래프를 PNG 이미지 파일로 저장하는 함수.
     * MediaStore API를 사용하여 공용 'Pictures/NeuroNicleApp' 폴더에 저장합니다.
     */
    private fun saveChartAsImage() {
        // 파일 이름 생성을 위한 현재 시간 포맷 (예: "20251018_130000")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 최종 파일 이름 조합 (예: "EEG_CHART_Subject1_20251018_130000.png")
        val fileName = "EEG_CHART_${subjectId}_$timestamp.png"

        // 파일 저장을 위한 정보 설정 (파일명, MIME 타입, 저장 경로 등)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) // 파일명 설정
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png") // 파일 타입 설정 (PNG 이미지)
            // 안드로이드 Q (API 29) 이상 버전에서는 저장될 상대 경로 지정
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 이미지를 공용 Pictures 폴더 아래 NeuroNicleApp 폴더에 저장
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NeuroNicleApp")
            }
        }

        // 파일을 저장하기 위한 로직 (MediaStore API 사용)
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { imageUri -> // ⭐ URI를 imageUri 변수로 받음
                contentResolver.openOutputStream(imageUri)?.use { stream ->
                    eegChart.getChartBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                currentPngUriString = imageUri.toString() // ⭐ 성공 시 URI 저장
                Toast.makeText(this, "그래프 이미지 저장 완료", Toast.LENGTH_SHORT).show()

                // ⭐ CSV와 PNG 모두 저장이 완료되었으면 기록 저장
                if (currentCsvUriString != null) {
                    saveHistoryItem(HistoryItem(System.currentTimeMillis(), subjectId, currentCsvUriString, currentPngUriString))
                    currentCsvUriString = null // 다음 저장을 위해 초기화
                    currentPngUriString = null
                }

            } ?: throw IOException("URI 생성 실패")
        } catch (e: Exception) { // 파일 저장 중 발생할 수 있는 모든 예외 처리
            Log.e(TAG, "Failed to save chart image", e) // 에러 로그 기록
            // 사용자에게 저장 실패 메시지 표시
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

    /**
     * 사용자가 ListView에서 선택한 BluetoothDevice에 연결을 시도하는 함수.
     * 연결 성공 시 사용자에게 알리고, 데이터 수신/파싱 작업을 시작합니다.
     * 연결 실패 시 사용자에게 알립니다.
     * @param device 연결할 BluetoothDevice 객체
     */
    private fun connectToDevice(device: BluetoothDevice) {
        // 코루틴을 사용하여 백그라운드(IO Dispatcher)에서 네트워크 통신(연결) 시도
        lifecycleScope.launch(Dispatchers.IO) {
            // 연결 시도 전에 UI 스레드에서 사용자에게 연결 중임을 알림
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
            }
            try {
                // 이미 열려 있는 소켓이 있다면 닫아서 리소스 정리
                bluetoothSocket?.close()
                // 선택된 BluetoothDevice 객체와 SPP UUID를 사용하여 BluetoothSocket 생성
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                // 소켓 연결 시도 (Blocking call - 시간이 걸릴 수 있음)
                bluetoothSocket?.connect()

                // 연결 성공 시 UI 스레드로 전환하여 사용자에게 알리고 데이터 수신 시작
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_LONG).show()
                    // 기존 데이터 수신 작업(Job)이 실행 중이라면 취소
                    dataReadingJob?.cancel()
                    // 새로운 데이터 수신 및 파싱 작업을 시작하고 Job 객체를 dataReadingJob에 저장
                    // bluetoothSocket이 null이 아닐 경우에만 parseAndReadData 호출 (let 사용)
                    dataReadingJob = bluetoothSocket?.let { parseAndReadData(it) }
                }
            } catch (e: IOException) { // 소켓 생성 또는 connect() 중 오류 발생 시
                Log.e(TAG, "Socket connection failed", e) // 에러 로그 기록
                // UI 스레드로 전환하여 사용자에게 연결 실패 알림
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection failed.", Toast.LENGTH_LONG).show()
                }
                // 실패 시 소켓을 닫으려고 시도 (오류 발생 가능성 있으므로 try-catch)
                try {
                    bluetoothSocket?.close()
                } catch (closeException: IOException) {
                    // 소켓 닫기 실패 시 로그만 기록 (무시)
                    Log.e(TAG, "Failed to close socket after connection error", closeException)
                }
            }
        }
    }

    /**
     * 주어진 BluetoothSocket으로부터 데이터를 읽고, neuroNicle E2 패킷을 파싱하여
     * EEG 데이터를 UI에 업데이트하고 그래프에 추가하는 작업을 수행하는 코루틴을 시작합니다.
     * @param socket 데이터를 읽어올 BluetoothSocket 객체.
     * @return 데이터 읽기 작업을 제어하는 Job 객체.
     */
    private fun parseAndReadData(socket: BluetoothSocket): Job {
        startTimeMillis = System.currentTimeMillis()
        // 코루틴을 시작하고, IO Dispatcher를 사용하여 백그라운드 스레드에서 실행합니다.
        return lifecycleScope.launch(Dispatchers.IO) {
            // 소켓으로부터 데이터를 읽기 위한 InputStream을 얻습니다.
            val inputStream: InputStream = socket.inputStream
            // 수신된 바이트 데이터를 임시로 저장할 가변 리스트(버퍼)를 생성합니다.
            val dataBuffer = mutableListOf<Byte>()
            // InputStream에서 한 번에 읽어올 데이터를 담을 ByteArray(버퍼)를 생성합니다. (크기: 1024 바이트)
            val readBuffer = ByteArray(1024)

            // 코루틴이 활성 상태인 동안 계속 반복합니다 (isActive 플래그 확인).
            while (isActive) {
                try {
                    // InputStream에서 데이터를 읽어 readBuffer에 저장하고, 읽은 바이트 수를 bytesRead에 저장합니다.
                    // 데이터가 없으면 읽을 때까지 대기합니다 (blocking call).
                    val bytesRead = inputStream.read(readBuffer)
                    // 읽은 바이트 수가 0보다 크면 (즉, 데이터를 읽었다면)
                    if (bytesRead > 0) {
                        // 읽은 만큼(bytesRead 개수)의 바이트를 readBuffer에서 가져와 dataBuffer 끝에 추가합니다.
                        dataBuffer.addAll(readBuffer.take(bytesRead))

                        // 패킷의 시작을 나타내는 동기화 바이트(Sync Bytes: 0xFF 다음 0xFE)를 찾습니다.
                        var syncIndex = -1 // 찾지 못했을 경우 기본값 -1
                        // dataBuffer의 처음부터 마지막 두 바이트 전까지 반복하며 FF, FE 패턴을 찾습니다.
                        for (i in 0 until dataBuffer.size - 1) {
                            if (dataBuffer[i] == 0xFF.toByte() && dataBuffer[i + 1] == 0xFE.toByte()) {
                                syncIndex = i // 찾으면 해당 인덱스를 저장하고
                                break       // 반복문을 종료합니다.
                            }
                        }

                        // 동기화 바이트를 찾았다면 (syncIndex가 -1이 아니라면)
                        if (syncIndex != -1) {
                            // 동기화 바이트 이전의 불필요한 데이터가 있다면 (syncIndex > 0)
                            if (syncIndex > 0) {
                                // 해당 데이터를 dataBuffer에서 제거합니다.
                                dataBuffer.subList(0, syncIndex).clear()
                            }

                            // dataBuffer에 완전한 패킷(PACKET_LENGTH = 36 바이트)이 하나 이상 존재할 때까지 반복합니다.
                            while (dataBuffer.size >= PACKET_LENGTH) {
                                // dataBuffer의 맨 앞에서부터 PACKET_LENGTH 만큼의 바이트를 가져와 packet 변수에 저장합니다.
                                val packet = dataBuffer.take(PACKET_LENGTH).toByteArray()
                                // parsePacket 함수를 호출하여 바이트 배열(packet)을 EegData 객체로 변환합니다.
                                val eegData = parsePacket(packet)


                                // 데이터 저장 모드(isSaving)가 활성화되어 있다면
                                if (isSaving) {
                                    // 여러 스레드에서 eegDataBuffer에 동시에 접근하는 것을 방지하기 위해 synchronized 블록 사용
                                    synchronized(eegDataBuffer) {
                                        // 변환된 EegData 객체를 저장용 버퍼(eegDataBuffer)에 추가합니다.
                                        eegDataBuffer.add(eegData)
                                    }
                                }

                                // UI 업데이트를 위해 메인 스레드(UI 스레드)로 컨텍스트를 전환합니다.
                                withContext(Dispatchers.Main) {
                                    // dataTextView에 현재 채널 1, 2의 uV 값을 소수점 둘째 자리까지 표시합니다.
                                    dataTextView.text = "CH1: %.2f µV\nCH2: %.2f µV".format(eegData.channel1, eegData.channel2)
                                    // addChartEntry 함수를 호출하여 그래프에 새로운 데이터 포인트를 추가합니다.
                                    addChartEntry(eegData)
                                    // detectState 함수를 호출하여 뇌파 상태를 분석하고 UI에 표시합니다. (현재는 주석 처리됨)
                                    // detectState(eegData)
                                }
                                // 처리된 패킷(PACKET_LENGTH 만큼)을 dataBuffer의 앞에서부터 제거합니다.
                                dataBuffer.subList(0, PACKET_LENGTH).clear()
                            }
                        }
                    }
                } catch (e: IOException) { // 데이터 읽기 중 오류 발생 시 (예: 블루투스 연결 끊김)
                    // UI 스레드로 전환하여 사용자에게 연결 끊김 메시지를 표시합니다.
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Connection lost.", Toast.LENGTH_LONG).show() }
                    // 오류 발생 시 while 루프를 종료합니다.
                    break
                }
            }
        }
    }
    // ⭐ SharedPreferences에 Gson을 사용하여 기록 목록을 저장하는 함수 (수정됨)
    private fun saveHistoryItem(item: HistoryItem) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson() // Gson 객체 생성

        // 1. 기존 기록 불러오기 (JSON 문자열 -> List<HistoryItem>)
        val historyJson = prefs.getString(HISTORY_KEY, null) // 기본값을 null로 변경
        val historyList: MutableList<HistoryItem> = if (historyJson != null) {
            // TypeToken을 사용하여 List<HistoryItem> 타입 정보 제공
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            try {
                gson.fromJson(historyJson, type) // JSON 문자열을 리스트 객체로 변환
            } catch (e: Exception) {
                Log.e(TAG, "기존 기록 JSON 파싱 오류", e)
                mutableListOf() // 파싱 오류 시 새 리스트 생성
            }
        } else {
            mutableListOf() // 기존 기록이 없으면 새 리스트 생성
        }

        // 2. 새 기록 항목을 리스트 맨 앞에 추가
        historyList.add(0, item) // 최신 항목이 맨 위로 가도록

        // 3. 업데이트된 리스트를 다시 JSON 문자열로 변환
        val updatedHistoryJson = gson.toJson(historyList)

        // 4. SharedPreferences에 저장
        prefs.edit().putString(HISTORY_KEY, updatedHistoryJson).apply()
        Log.d(TAG, "측정 기록 저장 완료: ${historyList.size}개 항목")
    }
    /**
     * neuroNicle E2 기기에서 수신된 36바이트 패킷(ByteArray)을 파싱하여
     * 채널 1과 채널 2의 뇌파 값(uV)을 포함하는 EegData 객체로 변환합니다.
     * @param packet 파싱할 36바이트 데이터 패킷.
     * @return 타임스탬프와 채널별 uV 값을 포함하는 EegData 객체.
     */
    private fun parsePacket(packet: ByteArray): EegData {
        // Little Endian 형식의 2바이트(패킷의 8번째, 9번째 인덱스)를 부호 있는 16비트 정수(Int)로 변환하여 ch1Raw에 저장합니다.
        // Kotlin의 Byte는 부호가 있으므로, 정확한 16비트 값을 얻기 위해 비트 연산을 사용합니다.
        // packet[9]를 Int로 변환하고 8비트 왼쪽으로 시프트한 값과,
        // packet[8]을 Int로 변환하고 하위 8비트만 남긴 값(0xFF와 AND 연산)을 OR 연산합니다.
        val ch1Raw = ((packet[9].toInt() shl 8) or (packet[8].toInt() and 0xFF))
        // 동일한 방식으로 패킷의 10번째, 11번째 인덱스를 사용하여 ch2Raw 값을 계산합니다.
        val ch2Raw = ((packet[11].toInt() shl 8) or (packet[10].toInt() and 0xFF))

        // Raw 데이터를 실제 uV(마이크로볼트) 값으로 변환하기 위한 계수입니다.
        // 이 값은 기기 매뉴얼의 스펙(최대 +/- 393uVp, 15/16비트 분해능)을 기반으로 추정되었습니다. (393.0f / 32767.0f)
        val conversionFactor = 0.01199f // 근사값

        // 계산된 Raw 값에 변환 계수를 곱하여 실제 uV 값을 얻습니다. (오프셋은 없다고 가정)
        val ch1uV = ch1Raw * conversionFactor
        val ch2uV = ch2Raw * conversionFactor

        // 현재 시스템 시간(밀리초 단위 타임스탬프)과 계산된 채널별 uV 값을 사용하여 EegData 객체를 생성하고 반환합니다.
        return EegData(System.currentTimeMillis(), ch1uV, ch2uV)
    }
    fun loadHistoryItems(context: android.content.Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val historyString = prefs.getString(HISTORY_KEY, "") ?: ""
        val items = mutableListOf<HistoryItem>()
        if (historyString.isNotEmpty()) {
            historyString.split(";").forEach { record ->
                val parts = record.split("|")
                if (parts.size == 4) {
                    try {
                        items.add(HistoryItem(parts[0].toLong(), parts[1], parts[2], parts[3]))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing history item: $record", e)
                    }
                }
            }
        }
        return items.reversed() // 최신 항목이 위로 오도록
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
