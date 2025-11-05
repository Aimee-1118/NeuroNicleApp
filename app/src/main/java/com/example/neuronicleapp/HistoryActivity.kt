package com.example.neuronicleapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupMenu // ⭐ PopupMenu import
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryActivity : AppCompatActivity() {

    private lateinit var historyListView: ListView
    private lateinit var historyAdapter: ArrayAdapter<HistoryItem> // ⭐ ArrayAdapter 타입 변경
    private var historyItems: List<HistoryItem> = listOf() // ⭐ 기록 아이템 리스트

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // UP 버튼 활성화
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        historyListView = findViewById(R.id.history_listview)

        // SharedPreferences에서 기록 불러오기 (MainActivity에 정의된 함수 사용)
        historyItems = loadHistoryItems(this)

        // ListView 어댑터 설정
        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyItems)
        historyListView.adapter = historyAdapter

        // 리스트 아이템 클릭 리스너 설정
        historyListView.setOnItemClickListener { parent, view, position, id ->
            val selectedItem = historyItems[position]
            // 팝업 메뉴 보여주기
            showOpenFileMenu(view, selectedItem)
        }
    }

    // ⭐ 파일 열기 팝업 메뉴 표시 함수
    private fun showOpenFileMenu(anchorView: android.view.View, item: HistoryItem) {
        val popup = PopupMenu(this, anchorView)
        popup.menuInflater.inflate(R.menu.history_item_menu, popup.menu) // 메뉴 리소스 필요

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.open_csv -> {
                    item.csvPath?.let { openFile(it, "text/plain") } ?: showToast("CSV 파일 없음")
                    true
                }
                R.id.open_png -> {
                    item.pngPath?.let { openFile(it, "image/png") } ?: showToast("PNG 파일 없음")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ⭐ URI를 사용하여 파일을 열 수 있는 외부 앱 호출 함수
    private fun openFile(uriString: String, mimeType: String) {
        try {
            val fileUri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                // 중요: 다른 앱이 URI에 접근할 수 있도록 권한 부여
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 해당 인텐트를 처리할 수 있는 앱이 있는지 확인
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                showToast("$mimeType 파일을 열 수 있는 앱이 없습니다.")
            }
        } catch (e: Exception) {
            Log.e("HistoryActivity", "파일 열기 실패: $uriString", e)
            showToast("파일을 열 수 없습니다.")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // MainActivity에 정의된 loadHistoryItems 함수 복사 (또는 별도 클래스로 분리)
    private fun loadHistoryItems(context: Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val historyJson = prefs.getString(HISTORY_KEY, null)
        return if (historyJson != null) {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            try {
                gson.fromJson(historyJson, type)
            } catch (e: Exception) {
                Log.e("HistoryActivity", "기록 JSON 파싱 오류", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                // UP 버튼(뒤로가기) 클릭 시 MainActivity로 돌아가기
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        // SharedPreferences 관련 상수 (MainActivity와 동일하게 정의)
        private const val PREFS_NAME = "MeasurementHistoryPrefs"
        private const val HISTORY_KEY = "measurementHistory"
    }
}
