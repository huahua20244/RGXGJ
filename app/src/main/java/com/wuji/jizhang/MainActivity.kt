package com.wuji.jizhang

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.max
import kotlin.math.min

data class Record(val id: Int, val type: String, val category: String, val amount: Double, val note: String, val date: String)
data class CalendarDay(val dayNumber: Int, val dateStr: String, val netBalance: Double, val isCurrentMonth: Boolean, var isSelected: Boolean)
data class MedCalendarDay(val dayNumber: Int, val dateStr: String, val isCurrentMonth: Boolean, val status: Int)

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: RecordDbHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecordAdapter
    private lateinit var tvDailyExpense: TextView
    private lateinit var tvSelectedDate: TextView

    private lateinit var rvCalendarGrid: RecyclerView
    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var tvMonthTitle: TextView
    private var currentCalendarMonth = Calendar.getInstance()
    private val calendarDaysList = ArrayList<CalendarDay>()

    private lateinit var rvMedCalendarGrid: RecyclerView
    private lateinit var medCalendarAdapter: MedCalendarAdapter
    private lateinit var tvMedMonthTitle: TextView
    private var currentMedCalendarMonth = Calendar.getInstance()
    private val medCalendarDaysList = ArrayList<MedCalendarDay>()

    private lateinit var expenseChartView: SimpleLineChartView
    private lateinit var incomeChartView: SimpleLineChartView

    private val recordList = ArrayList<Record>()
    private var currentDateStr = ""
    private var selectedDateStr = ""

    private lateinit var pageExpense: View
    private lateinit var pageSchedule: View
    private lateinit var pageMedication: View

    private val pastelColors = arrayOf("#F3E5F5", "#E8F5E9", "#FFF9C4", "#E1F5FE", "#FCE4EC", "#FBE9E7")
    private var userSelectedWeek: Int? = null
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        pageExpense = findViewById(R.id.pageExpense)
        pageSchedule = findViewById(R.id.pageSchedule)
        pageMedication = findViewById(R.id.pageMedication)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        currentDateStr = sdf.format(Date())
        selectedDateStr = currentDateStr

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_expense -> {
                    pageExpense.visibility = View.VISIBLE; pageSchedule.visibility = View.GONE; pageMedication.visibility = View.GONE
                    true
                }
                R.id.nav_schedule -> {
                    pageExpense.visibility = View.GONE; pageSchedule.visibility = View.VISIBLE; pageMedication.visibility = View.GONE
                    renderSchedule()
                    true
                }
                R.id.nav_medication -> {
                    pageExpense.visibility = View.GONE; pageSchedule.visibility = View.GONE; pageMedication.visibility = View.VISIBLE
                    updateMedCalendarUI()
                    true
                }
                else -> false
            }
        }

        dbHelper = RecordDbHelper(this)
        tvDailyExpense = findViewById(R.id.tvDailyExpense)
        tvSelectedDate = findViewById(R.id.tvSelectedDate)
        tvMonthTitle = findViewById(R.id.tvMonthTitle)

        findViewById<LinearLayout>(R.id.layoutBudget).setOnClickListener { showSetBudgetDialog() }
        findViewById<TextView>(R.id.btnStats).setOnClickListener { showStatisticsDialog() }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { showSearchDialog() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RecordAdapter(recordList) { clickedRecord -> showAddOrEditRecordDialog(clickedRecord) }
        recyclerView.adapter = adapter
        setupSwipeToDelete()

        expenseChartView = SimpleLineChartView(this).apply { setThemeColor("#E53935") }
        findViewById<FrameLayout>(R.id.chartContainerExpense).addView(expenseChartView)
        incomeChartView = SimpleLineChartView(this).apply { setThemeColor("#FF9800") }
        findViewById<FrameLayout>(R.id.chartContainerIncome).addView(incomeChartView)

        rvCalendarGrid = findViewById(R.id.rvCalendarGrid)
        rvCalendarGrid.layoutManager = GridLayoutManager(this, 7)
        calendarAdapter = CalendarAdapter(calendarDaysList) { clickedDay ->
            if (clickedDay.isCurrentMonth) { selectedDateStr = clickedDay.dateStr; updateCalendarUI(); loadDataForDate(selectedDateStr) }
        }
        rvCalendarGrid.adapter = calendarAdapter

        findViewById<ImageButton>(R.id.btnPrevMonth).setOnClickListener { currentCalendarMonth.add(Calendar.MONTH, -1); updateCalendarUI(); updateChartData() }
        findViewById<ImageButton>(R.id.btnNextMonth).setOnClickListener { currentCalendarMonth.add(Calendar.MONTH, 1); updateCalendarUI(); updateChartData() }
        findViewById<FloatingActionButton>(R.id.fabAddExpense).setOnClickListener { showAddOrEditRecordDialog(null) }

        updateCalendarUI(); loadDataForDate(selectedDateStr); updateChartData()

        tvMedMonthTitle = findViewById(R.id.tvMedMonthTitle)
        rvMedCalendarGrid = findViewById(R.id.rvMedCalendarGrid)
        rvMedCalendarGrid.layoutManager = GridLayoutManager(this, 7)
        medCalendarAdapter = MedCalendarAdapter(medCalendarDaysList) { clickedDay ->
            if (clickedDay.isCurrentMonth) { toggleSpecificDayPunch(clickedDay.dateStr) }
        }
        rvMedCalendarGrid.adapter = medCalendarAdapter

        findViewById<ImageButton>(R.id.btnMedPrevMonth).setOnClickListener { currentMedCalendarMonth.add(Calendar.MONTH, -1); updateMedCalendarUI() }
        findViewById<ImageButton>(R.id.btnMedNextMonth).setOnClickListener { currentMedCalendarMonth.add(Calendar.MONTH, 1); updateMedCalendarUI() }
        findViewById<androidx.cardview.widget.CardView>(R.id.btnPunchMed).setOnClickListener { toggleSpecificDayPunch(currentDateStr) }

        setupReminderUI()
        findViewById<FloatingActionButton>(R.id.fabAddCourse).setOnClickListener { showAddCourseDialog() }
        findViewById<TextView>(R.id.btnShowImport).setOnClickListener { showWebViewImportDialog() }

        // 滑动引擎
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e2 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) changeWeekBy(-1) else changeWeekBy(1)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pageSchedule.visibility == View.VISIBLE) gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun changeWeekBy(offset: Int) {
        val prefs = getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val savedStartDate = prefs.getString("semester_start_date", "2026-02-23") ?: "2026-02-23"
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val termStartDate = try { sdf.parse(savedStartDate) ?: Date() } catch (e: Exception) { Date() }

        val calStart = Calendar.getInstance().apply { time = termStartDate; firstDayOfWeek = Calendar.MONDAY }
        calStart.set(Calendar.HOUR_OF_DAY, 0); calStart.set(Calendar.MINUTE, 0); calStart.set(Calendar.SECOND, 0); calStart.set(Calendar.MILLISECOND, 0)
        val calNow = Calendar.getInstance()
        calNow.set(Calendar.HOUR_OF_DAY, 0); calNow.set(Calendar.MINUTE, 0); calNow.set(Calendar.SECOND, 0); calNow.set(Calendar.MILLISECOND, 0)
        val diffDays = ((calNow.timeInMillis - calStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        val actualCurrentWeek = if (diffDays < 0) 1 else (diffDays / 7) + 1

        val current = userSelectedWeek ?: actualCurrentWeek
        var newWeek = current + offset
        if (newWeek < 1) newWeek = 1
        if (newWeek > 25) newWeek = 25
        if (current == newWeek) return

        userSelectedWeek = newWeek

        val courseGrid = findViewById<FrameLayout>(R.id.courseGrid)
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val outX = if (offset > 0) -screenWidth else screenWidth
        val inX = if (offset > 0) screenWidth else -screenWidth

        courseGrid.animate().cancel()
        courseGrid.animate().translationX(outX).setDuration(120).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
            renderSchedule()
            courseGrid.translationX = inX
            courseGrid.animate().translationX(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }.start()
    }

    private fun showWebViewImportDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = floatArrayOf(dpToPx(24f).toFloat(), dpToPx(24f).toFloat(), dpToPx(24f).toFloat(), dpToPx(24f).toFloat(), 0f, 0f, 0f, 0f) }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.90).toInt())
        }

        val header = RelativeLayout(this).apply { setPadding(dpToPx(16f), dpToPx(16f), dpToPx(16f), dpToPx(16f)) }
        val btnCancel = TextView(this).apply { text = "取消"; textSize = 16f; setTextColor(Color.parseColor("#666666")); setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f)); setOnClickListener { dialog.dismiss() } }
        val cancelParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_START); addRule(RelativeLayout.CENTER_VERTICAL) }
        val tvTitle = TextView(this).apply { text = "安全直连教务系统"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#333333")) }
        val titleParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
        header.addView(btnCancel, cancelParams)
        header.addView(tvTitle, titleParams)

        val noteBanner = TextView(this).apply {
            text = "💡 登录后直接点击你想查询的学期课表\n只要表格一出现，App就会自动全盘提取！"
            textSize = 13f; setTextColor(Color.parseColor("#E65100")); setBackgroundColor(Color.parseColor("#FFF3E0")); setPadding(dpToPx(16f), dpToPx(12f), dpToPx(16f), dpToPx(12f)); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        container.addView(header); container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1f)); setBackgroundColor(Color.parseColor("#EEEEEE")) }); container.addView(noteBanner); container.addView(webView)
        dialog.setContentView(container)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        val bottomSheet = dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
        bottomSheet?.let { BottomSheetBehavior.from(it).apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true } }
        dialog.show()

        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun processHTML(html: String) {
                if (!html.contains("一单元节") || !html.contains("星期")) return
                runOnUiThread { Toast.makeText(this@MainActivity, "📡 监测到课表！正在瞬间提取...", Toast.LENGTH_SHORT).show() }

                try {
                    val doc = Jsoup.parse(html)
                    val db = dbHelper.writableDatabase
                    db.execSQL("DELETE FROM courses")
                    val rows = doc.select("table[bordercolor=#000000] tr")
                    var coursesFound = 0

                    for (row in rows) {
                        val tds = row.select("td")
                        var dayOffset = -1
                        val rowText = row.text()

                        if (rowText.contains("一单元节")) dayOffset = tds.indexOfFirst { it.text().contains("一单元节") } + 1
                        else if (rowText.contains("二单元节")) dayOffset = tds.indexOfFirst { it.text().contains("二单元节") } + 1
                        else if (rowText.contains("三单元节")) dayOffset = tds.indexOfFirst { it.text().contains("三单元节") } + 1
                        else if (rowText.contains("四单元节")) dayOffset = tds.indexOfFirst { it.text().contains("四单元节") } + 1
                        else if (rowText.contains("五单元节")) dayOffset = tds.indexOfFirst { it.text().contains("五单元节") } + 1

                        if (dayOffset != -1 && dayOffset > 0) {
                            for (i in 0 until 7) {
                                val cellIndex = dayOffset + i
                                if (cellIndex < tds.size) {
                                    val cellHtml = tds[cellIndex].html()
                                    val parts = cellHtml.split(Regex("(?i)<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }.filter { it.isNotEmpty() && it != "&nbsp;" && it != " " }

                                    var idx = 0
                                    while (idx + 4 < parts.size) {
                                        if (parts[idx + 1].matches(Regex(".*[\\(（]\\d+-\\d+节[\\)）].*"))) {
                                            val name = parts[idx]
                                            val sectionStr = parts[idx + 1]
                                            val teacher = if (idx + 2 < parts.size) parts[idx + 2] else "未知"
                                            val room = if (idx + 3 < parts.size) parts[idx + 3] else "未知"
                                            val weekStr = if (idx + 4 < parts.size) parts[idx + 4] else "1-20"

                                            val match = Regex("[\\(（](\\d+)-(\\d+)节[\\)）]").find(sectionStr)
                                            if (match != null) {
                                                val startSection = match.groupValues[1].toInt()
                                                val endSection = match.groupValues[2].toInt()
                                                val dayOfWeek = i + 1
                                                var startWeek = 1
                                                var endWeek = 20
                                                val weekMatch = Regex("(\\d+)-(\\d+)").find(weekStr)
                                                if (weekMatch != null) { startWeek = weekMatch.groupValues[1].toInt(); endWeek = weekMatch.groupValues[2].toInt() }

                                                val color = pastelColors[Random().nextInt(pastelColors.size)]
                                                val cv = ContentValues().apply {
                                                    put("name", name); put("room", room); put("dayOfWeek", dayOfWeek); put("startSection", startSection); put("endSection", endSection); put("color", color); put("startWeek", startWeek); put("endWeek", endWeek); put("teacher", teacher)
                                                }
                                                db.insert("courses", null, cv)
                                                coursesFound++
                                            }
                                            idx += 5
                                        } else {
                                            idx++
                                        }
                                    }
                                }
                            }
                        }
                    }
                    runOnUiThread {
                        if (coursesFound > 0) {
                            Toast.makeText(this@MainActivity, "🎉 完美！提取到 $coursesFound 门课程", Toast.LENGTH_LONG).show()
                            userSelectedWeek = null
                            renderSchedule()
                            dialog.dismiss()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }, "HTMLOUT")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.loadUrl("javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML);")
            }
        }
        webView.loadUrl("http://jwc.sasu.edu.cn/web/web/web/index.asp")
    }

    // ==========================================
    // 🌟 终极时间渲染引擎：包含日期动态注入与精准灰度算法 🌟
    // ==========================================
    private fun renderSchedule() {
        val layoutTimeColumn = findViewById<LinearLayout>(R.id.layoutTimeColumn)
        val courseGrid = findViewById<FrameLayout>(R.id.courseGrid)
        layoutTimeColumn.removeAllViews()
        courseGrid.removeAllViews()

        val rowHeight = dpToPx(65f)
        val leftColWidth = dpToPx(35f)
        val screenWidth = resources.displayMetrics.widthPixels
        val columnWidth = (screenWidth - leftColWidth) / 7

        // 1. 获取动态配置的开学日期
        val prefs = getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val savedStartDate = prefs.getString("semester_start_date", "2026-02-23") ?: "2026-02-23"

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val termStartDate = try { sdf.parse(savedStartDate) ?: Date() } catch (e: Exception) { Date() }

        // 2. 精准推算当前自然周
        val calStart = Calendar.getInstance().apply { time = termStartDate; firstDayOfWeek = Calendar.MONDAY }
        calStart.set(Calendar.HOUR_OF_DAY, 0); calStart.set(Calendar.MINUTE, 0); calStart.set(Calendar.SECOND, 0); calStart.set(Calendar.MILLISECOND, 0)

        val calNow = Calendar.getInstance()
        calNow.set(Calendar.HOUR_OF_DAY, 0); calNow.set(Calendar.MINUTE, 0); calNow.set(Calendar.SECOND, 0); calNow.set(Calendar.MILLISECOND, 0)

        val diffDays = ((calNow.timeInMillis - calStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        val actualCurrentWeek = if (diffDays < 0) 1 else (diffDays / 7) + 1
        val isReallyVacation = actualCurrentWeek < 1 || actualCurrentWeek > 25

        val displayWeek = userSelectedWeek ?: actualCurrentWeek

        // 3. 更新标题并绑定隐藏菜单 (长按修改开学日期)
        val tvScheduleTitle = findViewById<TextView>(R.id.tvScheduleTitle)
        if (isReallyVacation && userSelectedWeek == null) {
            tvScheduleTitle.text = "假期中 ▾"
            tvScheduleTitle.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            val suffix = if (displayWeek == actualCurrentWeek) " (本周)" else " (非本周)"
            tvScheduleTitle.text = "第 $displayWeek 周$suffix ▾"
            tvScheduleTitle.setTextColor(Color.parseColor("#333333"))
        }

        // 🌟 长按标题：呼出开学日期设置彩蛋 🌟
        tvScheduleTitle.setOnLongClickListener {
            val et = EditText(this).apply {
                setText(savedStartDate)
                gravity = Gravity.CENTER
                textSize = 18f
                setPadding(dpToPx(16f), dpToPx(16f), dpToPx(16f), dpToPx(16f))
            }
            AlertDialog.Builder(this)
                .setTitle("⚙️ 设置开学日期")
                .setMessage("格式：YYYY-MM-DD\nApp将以此日期(周一)为起点推算所有周次和日期。")
                .setView(et)
                .setPositiveButton("保存并重绘") { _, _ ->
                    val newDate = et.text.toString().trim()
                    try {
                        sdf.parse(newDate) // 验证格式
                        prefs.edit().putString("semester_start_date", newDate).apply()
                        Toast.makeText(this, "开学日期已更新！", Toast.LENGTH_SHORT).show()
                        userSelectedWeek = null
                        renderSchedule()
                    } catch (e: Exception) {
                        Toast.makeText(this, "格式不正确，请严格按照 YYYY-MM-DD 填写", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        // 点击标题：选择周次
        tvScheduleTitle.setOnClickListener {
            val dialog = BottomSheetDialog(this)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = floatArrayOf(dpToPx(24f).toFloat(), dpToPx(24f).toFloat(), dpToPx(24f).toFloat(), dpToPx(24f).toFloat(), 0f, 0f, 0f, 0f) }
                setPadding(dpToPx(16f), dpToPx(20f), dpToPx(16f), dpToPx(32f))
            }
            val dragHandle = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#E0E0E0")); cornerRadius = dpToPx(2f).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dpToPx(36f), dpToPx(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpToPx(16f) }
            }
            container.addView(dragHandle)
            container.addView(TextView(this).apply { text = "选择查看周次"; textSize = 17f; setTextColor(Color.parseColor("#333333")); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, 0, 0, dpToPx(24f)) })
            val gridLayout = GridLayout(this).apply { columnCount = 5; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            val paddingTotal = dpToPx(32f)
            val cellWidth = (screenWidth - paddingTotal) / 5
            for (i in 1..25) {
                val isActualCurrent = (i == actualCurrentWeek && !isReallyVacation)
                val isSelected = (i == displayWeek)
                val cellContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    layoutParams = GridLayout.LayoutParams().apply { width = cellWidth - dpToPx(8f); height = dpToPx(54f); setMargins(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f)) }
                    if (isSelected) { background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#4C83FF")); cornerRadius = dpToPx(12f).toFloat() } }
                    else if (isActualCurrent) { background = android.graphics.drawable.GradientDrawable().apply { setStroke(dpToPx(1.5f), Color.parseColor("#4C83FF")); cornerRadius = dpToPx(12f).toFloat() } }
                    else { background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#F5F7FA")); cornerRadius = dpToPx(12f).toFloat() } }
                }
                val tvWeekNumber = TextView(this).apply { text = "$i"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); setTextColor(if (isSelected) Color.WHITE else if (isActualCurrent) Color.parseColor("#4C83FF") else Color.parseColor("#666666")) }
                cellContainer.addView(tvWeekNumber)
                if (isActualCurrent) {
                    val tvTag = TextView(this).apply { text = "本周"; textSize = 9f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(2f) }; setTextColor(if (isSelected) Color.parseColor("#D0E0FF") else Color.parseColor("#4C83FF")) }
                    cellContainer.addView(tvTag)
                }
                cellContainer.setOnClickListener { userSelectedWeek = i; renderSchedule(); dialog.dismiss() }
                gridLayout.addView(cellContainer)
            }
            container.addView(gridLayout); dialog.setContentView(container)
            dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
            dialog.show()
        }

        // 🌟 动态注入表头日期 (月/日) 🌟
        try {
            // 获取表头所在的 LinearLayout
            val rootLinear = (courseGrid.parent.parent.parent as ViewGroup)
            val headerLayout = rootLinear.getChildAt(1) as ViewGroup

            // 推算当前显示周的周一日期
            val calDate = Calendar.getInstance().apply { time = termStartDate; firstDayOfWeek = Calendar.MONDAY }
            calDate.add(Calendar.WEEK_OF_YEAR, displayWeek - 1)

            val sdfDate = SimpleDateFormat("MM/dd", Locale.getDefault())
            val daysOfWeekStr = arrayOf("一", "二", "三", "四", "五", "六", "日")

            val calToday = Calendar.getInstance()
            var todayIndex = calToday.get(Calendar.DAY_OF_WEEK) - 1
            if (todayIndex == 0) todayIndex = 7

            for (i in 1..7) {
                val dayContainer = headerLayout.getChildAt(i) as LinearLayout
                dayContainer.removeAllViews() // 清空旧的“周一”等文字

                val isToday = (displayWeek == actualCurrentWeek && i == todayIndex && !isReallyVacation)

                val tvDayName = TextView(this).apply {
                    text = "周${daysOfWeekStr[i-1]}"
                    textSize = 10f
                    setTextColor(if (isToday) Color.parseColor("#4C83FF") else Color.parseColor("#888888"))
                    if (isToday) typeface = Typeface.DEFAULT_BOLD
                }
                val tvDate = TextView(this).apply {
                    text = sdfDate.format(calDate.time)
                    textSize = 11f
                    setTextColor(if (isToday) Color.parseColor("#4C83FF") else Color.parseColor("#333333"))
                    if (isToday) typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dpToPx(2f), 0, 0)
                }

                dayContainer.addView(tvDayName)
                dayContainer.addView(tvDate)

                calDate.add(Calendar.DAY_OF_YEAR, 1) // 日期+1天
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 4. 获取当前精确时间
        val calNow2 = Calendar.getInstance()
        var currentDayOfWeek = calNow2.get(Calendar.DAY_OF_WEEK) - 1
        if (currentDayOfWeek == 0) currentDayOfWeek = 7
        val currentTotalMinutes = calNow2.get(Calendar.HOUR_OF_DAY) * 60 + calNow2.get(Calendar.MINUTE)
        val endTimes = intArrayOf(0, 525, 580, 645, 700, 915, 970, 1025, 1080, 1185, 1240, 1300, 1350)

        // 5. 左侧时间列
        val times = arrayOf("08:00\n08:45", "08:55\n09:40", "10:00\n10:45", "10:55\n11:40", "14:30\n15:15", "15:25\n16:10", "16:20\n17:05", "17:15\n18:00", "19:00\n19:45", "19:55\n20:40", "20:50\n21:35", "21:45\n22:30")
        for (i in 1..12) {
            val tvContainer = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(leftColWidth, rowHeight); orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val tvSection = TextView(this).apply { text = "$i"; textSize = 14f; setTextColor(Color.parseColor("#333333")); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
            val tvTime = TextView(this).apply { text = times[i-1]; textSize = 9f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) }
            tvContainer.addView(tvSection); tvContainer.addView(tvTime); layoutTimeColumn.addView(tvContainer)

            val line = View(this).apply { layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply { topMargin = i * rowHeight }; setBackgroundColor(Color.parseColor("#F0F0F0")) }
            courseGrid.addView(line)
        }

        // 6. 渲染课程（🌟 完美逻辑：过去置灰，未来保持彩色）
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM courses", null)
        while (cursor.moveToNext()) {
            val name = cursor.getString(1)
            val room = cursor.getString(2)
            val day = cursor.getInt(3)
            val start = cursor.getInt(4)
            val end = cursor.getInt(5)
            val colorStr = cursor.getString(6)
            val startWeek = cursor.getInt(7)
            val endWeek = cursor.getInt(8)
            val teacherIndex = cursor.getColumnIndex("teacher")
            val teacher = if (teacherIndex != -1) cursor.getString(teacherIndex) else "未知"

            var isGray = false

            if (displayWeek !in startWeek..endWeek) {
                isGray = true // 如果该周没这门课，变灰
            } else if (displayWeek < actualCurrentWeek && !isReallyVacation) {
                isGray = true // 如果看的是过去的周次，全部变灰
            } else if (displayWeek == actualCurrentWeek && !isReallyVacation) {
                // 如果看的是本周，判断是否已经上过
                if (day < currentDayOfWeek) {
                    isGray = true // 昨天的课
                } else if (day == currentDayOfWeek && endTimes[end] < currentTotalMinutes) {
                    isGray = true // 今天的课但已经下课
                }
            }

            val finalBgColor = if (isGray) "#EAEAEA" else colorStr
            val finalTextColor = if (isGray) "#9E9E9E" else "#444444"

            val courseCard = CardView(this).apply {
                val span = end - start + 1
                val params = FrameLayout.LayoutParams(columnWidth - dpToPx(4f), span * rowHeight - dpToPx(4f))
                params.leftMargin = (day - 1) * columnWidth + dpToPx(2f)
                params.topMargin = (start - 1) * rowHeight + dpToPx(2f)
                layoutParams = params
                setCardBackgroundColor(Color.parseColor(finalBgColor))
                radius = dpToPx(8f).toFloat()
                cardElevation = 0f
            }

            val tvContent = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                text = "$name\n@$room"
                textSize = 11f
                setTextColor(Color.parseColor(finalTextColor))
                gravity = Gravity.CENTER
                setPadding(dpToPx(2f), dpToPx(4f), dpToPx(2f), dpToPx(4f))
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // 绑定点击事件，弹出超精美详情页
            courseCard.setOnClickListener {
                showCourseDetailDialog(name, room, start, end, day, startWeek, endWeek, teacher, isGray)
            }

            courseCard.addView(tvContent)
            courseGrid.addView(courseCard)
        }
        cursor.close()
    }

    private fun showCourseDetailDialog(name: String, room: String, start: Int, end: Int, day: Int, startWeek: Int, endWeek: Int, teacher: String, isGray: Boolean) {
        val dialog = AlertDialog.Builder(this).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#F4F6F9")); cornerRadius = dpToPx(16f).toFloat() }
            setPadding(dpToPx(20f), dpToPx(20f), dpToPx(20f), dpToPx(24f))
        }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

        val tagBgColor = if (isGray) "#90A4AE" else "#4C83FF"
        val tagText = if (isGray) "非本周/已结束" else "本周课程"
        val tvTag = TextView(this).apply { text = tagText; setTextColor(Color.WHITE); textSize = 12f; setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f)); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor(tagBgColor)); cornerRadius = dpToPx(4f).toFloat() }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(8f) } }

        val tvTitle = TextView(this).apply { text = name; textSize = 18f; setTextColor(Color.parseColor("#333333")); typeface = Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnEdit = TextView(this).apply { text = "编辑"; textSize = 12f; setTextColor(Color.parseColor("#888888")); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpToPx(4f).toFloat() }; setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f)); setOnClickListener { Toast.makeText(this@MainActivity, "编辑功能开发中~", Toast.LENGTH_SHORT).show() } }

        headerRow.addView(tvTag); headerRow.addView(tvTitle); headerRow.addView(btnEdit); container.addView(headerRow)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply { topMargin = dpToPx(16f); bottomMargin = dpToPx(16f) }; setBackgroundColor(Color.parseColor("#E0E0E0")) })

        val textStyle = { tv: TextView -> tv.textSize = 15f; tv.setTextColor(Color.parseColor("#555555")); tv.setPadding(0, 0, 0, dpToPx(12f)) }
        val startTimesStr = arrayOf("", "08:00", "08:55", "10:00", "10:55", "14:30", "15:25", "16:20", "17:15", "19:00", "19:55", "20:50", "21:45")
        val endTimesStr = arrayOf("", "08:45", "09:40", "10:45", "11:40", "15:15", "16:10", "17:05", "18:00", "19:45", "20:40", "21:35", "22:30")
        val daysStr = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

        val tvWeek = TextView(this).apply { text = "第${startWeek}-${endWeek}周"; textStyle(this) }
        val tvTime = TextView(this).apply { text = "${daysStr[day]} | 第${start}-${end}节 | ${startTimesStr[start]}-${endTimesStr[end]}"; textStyle(this) }
        val tvRoom = TextView(this).apply { text = "教室: $room"; textStyle(this) }
        val tvTeacher = TextView(this).apply { text = "老师: $teacher"; textStyle(this); setPadding(0,0,0,0) }

        container.addView(tvWeek); container.addView(tvTime); container.addView(tvRoom); container.addView(tvTeacher)
        dialog.setView(container); dialog.show()
    }

    private fun showAddCourseDialog() {
        val dialog = BottomSheetDialog(this); val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null); dialog.setContentView(view); dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        val etCourseName = view.findViewById<EditText>(R.id.etCourseName); val etRoom = view.findViewById<EditText>(R.id.etRoom); val spinnerDay = view.findViewById<Spinner>(R.id.spinnerDay); val spinnerStart = view.findViewById<Spinner>(R.id.spinnerStart); val spinnerEnd = view.findViewById<Spinner>(R.id.spinnerEnd); val btnSaveCourse = view.findViewById<Button>(R.id.btnSaveCourse)
        val days = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"); val sections = (1..12).map { "第${it}节" }.toTypedArray()
        spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, days); spinnerStart.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sections); spinnerEnd.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sections)
        btnSaveCourse.setOnClickListener {
            val name = etCourseName.text.toString(); val room = etRoom.text.toString(); val day = spinnerDay.selectedItemPosition + 1; val start = spinnerStart.selectedItemPosition + 1; val end = spinnerEnd.selectedItemPosition + 1
            if (name.isNotEmpty() && start <= end) { val color = pastelColors[Random().nextInt(pastelColors.size)]; val values = ContentValues().apply { put("name", name); put("room", room); put("dayOfWeek", day); put("startSection", start); put("endSection", end); put("color", color); put("startWeek", 1); put("endWeek", 20); put("teacher", "自定义") }; dbHelper.writableDatabase.insert("courses", null, values); renderSchedule(); dialog.dismiss() } else { Toast.makeText(this, "请填写名称并确保节次正确", Toast.LENGTH_SHORT).show() }
        }
        dialog.show()
    }

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    private fun setupReminderUI() {
        val cardSetReminder = findViewById<View>(R.id.cardSetReminder); val tvReminderTime = findViewById<TextView>(R.id.tvReminderTime); val prefs = getSharedPreferences("MedicationPrefs", Context.MODE_PRIVATE); val savedTime = prefs.getString("reminder_time", "未设置"); tvReminderTime.text = savedTime
        cardSetReminder.setOnClickListener { val dialog = BottomSheetDialog(this); val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null); dialog.setContentView(view); dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT); val pickerHour = view.findViewById<NumberPicker>(R.id.pickerHour); val pickerMinute = view.findViewById<NumberPicker>(R.id.pickerMinute); val btnSaveTime = view.findViewById<Button>(R.id.btnSaveTime); pickerHour.minValue = 0; pickerHour.maxValue = 23; pickerHour.setFormatter { i -> String.format("%02d", i) }; pickerMinute.minValue = 0; pickerMinute.maxValue = 59; pickerMinute.setFormatter { i -> String.format("%02d", i) }; val currentTime = tvReminderTime.text.toString(); if (currentTime != "未设置" && currentTime.contains(":")) { val parts = currentTime.split(":"); pickerHour.value = parts[0].toIntOrNull() ?: 8; pickerMinute.value = parts[1].toIntOrNull() ?: 0 } else { val calendar = Calendar.getInstance(); pickerHour.value = calendar.get(Calendar.HOUR_OF_DAY); pickerMinute.value = calendar.get(Calendar.MINUTE) }; btnSaveTime.setOnClickListener { val selectedHour = pickerHour.value; val selectedMinute = pickerMinute.value; val timeStr = String.format("%02d:%02d", selectedHour, selectedMinute); tvReminderTime.text = timeStr; prefs.edit().putString("reminder_time", timeStr).apply(); scheduleAlarm(selectedHour, selectedMinute); dialog.dismiss() }; dialog.show() }
    }

    private fun scheduleAlarm(hour: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager; val intent = Intent(this, MedicationReceiver::class.java); val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1) }; try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent) } else { alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent) }; Toast.makeText(this, "提醒已开启：每天 $hour:$minute", Toast.LENGTH_SHORT).show() } catch (e: SecurityException) { Toast.makeText(this, "请允许设置闹钟", Toast.LENGTH_LONG).show() }
    }

    private fun updateMedCalendarUI() {
        medCalendarDaysList.clear(); val displayMonthSdf = SimpleDateFormat("yyyy年M月 健康日历", Locale.getDefault()); tvMedMonthTitle.text = displayMonthSdf.format(currentMedCalendarMonth.time); val queryMonthSdf = SimpleDateFormat("yyyy-MM", Locale.getDefault()); val currentMonthStr = queryMonthSdf.format(currentMedCalendarMonth.time); val prefs = getSharedPreferences("MedicationPrefs", Context.MODE_PRIVATE); val punchedDays = prefs.getStringSet("punched_days", mutableSetOf()) ?: mutableSetOf(); val calendarCalc = currentMedCalendarMonth.clone() as Calendar; calendarCalc.set(Calendar.DAY_OF_MONTH, 1); var firstDayOfWeek = calendarCalc.get(Calendar.DAY_OF_WEEK); var blankDays = firstDayOfWeek - 2; if (blankDays < 0) blankDays = 6; for (i in 0 until blankDays) medCalendarDaysList.add(MedCalendarDay(0, "", false, 0)); val daysInMonth = calendarCalc.getActualMaximum(Calendar.DAY_OF_MONTH); for (day in 1..daysInMonth) { val dateStr = String.format("%s-%02d", currentMonthStr, day); var status = 0; if (punchedDays.contains(dateStr)) status = 1 else if (dateStr < currentDateStr) status = -1; medCalendarDaysList.add(MedCalendarDay(day, dateStr, true, status)) }; medCalendarAdapter.notifyDataSetChanged(); refreshMedicationDashboard(punchedDays)
    }

    private fun refreshMedicationDashboard(punchedDays: Set<String>) {
        val tvMedTitleDate = findViewById<TextView>(R.id.tvMedTitleDate); val btnPunchMed = findViewById<androidx.cardview.widget.CardView>(R.id.btnPunchMed); val tvPunchIcon = findViewById<TextView>(R.id.tvPunchIcon); val tvPunchText = findViewById<TextView>(R.id.tvPunchText); val tvPunchCount = findViewById<TextView>(R.id.tvPunchCount); val displaySdf = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()); tvMedTitleDate.text = displaySdf.format(Date()); if (punchedDays.contains(currentDateStr)) { btnPunchMed.setCardBackgroundColor(Color.parseColor("#43A047")); tvPunchIcon.visibility = View.VISIBLE; tvPunchIcon.text = "完成"; tvPunchText.text = "今日已吃药" } else { btnPunchMed.setCardBackgroundColor(Color.parseColor("#FF9800")); tvPunchIcon.visibility = View.GONE; tvPunchText.text = "点击打卡记录" }; val currentMonthPrefix = currentDateStr.substring(0, 7); val monthCount = punchedDays.count { it.startsWith(currentMonthPrefix) }; tvPunchCount.text = "本月已累计坚守: $monthCount 天"
    }

    private fun toggleSpecificDayPunch(dateStr: String) {
        val prefs = getSharedPreferences("MedicationPrefs", Context.MODE_PRIVATE); val punchedDays = prefs.getStringSet("punched_days", mutableSetOf())?.toMutableSet() ?: mutableSetOf(); if (punchedDays.contains(dateStr)) { punchedDays.remove(dateStr); if (dateStr == currentDateStr) Toast.makeText(this, "已撤销今日打卡", Toast.LENGTH_SHORT).show() } else { punchedDays.add(dateStr); if (dateStr == currentDateStr) Toast.makeText(this, "打卡成功，按时吃药身体好", Toast.LENGTH_SHORT).show() }; prefs.edit().putStringSet("punched_days", punchedDays).apply(); updateMedCalendarUI(); if (dateStr == currentDateStr) { val btnPunchMed = findViewById<androidx.cardview.widget.CardView>(R.id.btnPunchMed); btnPunchMed.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).withEndAction { btnPunchMed.animate().scaleX(1f).scaleY(1f).setDuration(100).start() }.start() }
    }

    private fun showSearchDialog() {
        val dialog = BottomSheetDialog(this); val view = LayoutInflater.from(this).inflate(R.layout.dialog_search, null); dialog.setContentView(view); dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT); val etSearch = view.findViewById<EditText>(R.id.etSearch); val rvSearchResults = view.findViewById<RecyclerView>(R.id.rvSearchResults); val searchResults = ArrayList<Record>(); val searchAdapter = RecordAdapter(searchResults) { clickedRecord -> dialog.dismiss(); showAddOrEditRecordDialog(clickedRecord) }; rvSearchResults.layoutManager = LinearLayoutManager(this); rvSearchResults.adapter = searchAdapter
        fun performSearch(keyword: String) { searchResults.clear(); searchAdapter.resetAnimation(); if (keyword.isNotEmpty()) { val db = dbHelper.readableDatabase; val cursor = db.rawQuery("SELECT * FROM records WHERE note LIKE ? OR category LIKE ? ORDER BY date DESC", arrayOf("%$keyword%", "%$keyword%")); with(cursor) { while (moveToNext()) { val id = getInt(getColumnIndexOrThrow("id")); val type = getString(getColumnIndexOrThrow("type")); val category = getString(getColumnIndexOrThrow("category")); val amount = getDouble(getColumnIndexOrThrow("amount")); val note = getString(getColumnIndexOrThrow("note")); val date = getString(getColumnIndexOrThrow("date")); searchResults.add(Record(id, type, category, amount, note, date)) } }; cursor.close() }; searchAdapter.notifyDataSetChanged() }
        etSearch.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { performSearch(s.toString().trim()) }; override fun afterTextChanged(s: Editable?) {} }); dialog.show(); etSearch.requestFocus()
    }

    private fun updateCalendarUI() {
        calendarDaysList.clear(); val displayMonthSdf = SimpleDateFormat("yyyy年M月", Locale.getDefault()); tvMonthTitle.text = displayMonthSdf.format(currentCalendarMonth.time); val queryMonthSdf = SimpleDateFormat("yyyy-MM", Locale.getDefault()); val currentMonthStr = queryMonthSdf.format(currentCalendarMonth.time); val netBalances = HashMap<String, Double>(); val db = dbHelper.readableDatabase; val cursor = db.rawQuery("SELECT date, type, amount FROM records WHERE date LIKE ?", arrayOf("$currentMonthStr%")); while (cursor.moveToNext()) { val fullDate = cursor.getString(0); val dateOnly = fullDate.substring(0, 10); val type = cursor.getString(1); val amount = cursor.getDouble(2); val currentNet = netBalances[dateOnly] ?: 0.0; if (type == "支出") netBalances[dateOnly] = currentNet - amount else netBalances[dateOnly] = currentNet + amount }; cursor.close(); val calendarCalc = currentCalendarMonth.clone() as Calendar; calendarCalc.set(Calendar.DAY_OF_MONTH, 1); var firstDayOfWeek = calendarCalc.get(Calendar.DAY_OF_WEEK); var blankDays = firstDayOfWeek - 2; if (blankDays < 0) blankDays = 6; for (i in 0 until blankDays) calendarDaysList.add(CalendarDay(0, "", 0.0, false, false)); val daysInMonth = calendarCalc.getActualMaximum(Calendar.DAY_OF_MONTH); for (day in 1..daysInMonth) { val dateStr = String.format("%s-%02d", currentMonthStr, day); val netBalance = netBalances[dateStr] ?: 0.0; val isSelected = (dateStr == selectedDateStr); calendarDaysList.add(CalendarDay(day, dateStr, netBalance, true, isSelected)) }; calendarAdapter.notifyDataSetChanged()
    }

    private fun showSetBudgetDialog() {
        val dialog = BottomSheetDialog(this); val view = LayoutInflater.from(this).inflate(R.layout.dialog_set_budget, null); dialog.setContentView(view); dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT); val etBudget = view.findViewById<EditText>(R.id.etBudget); val btnSaveBudget = view.findViewById<Button>(R.id.btnSaveBudget); val prefs = getSharedPreferences("ExpensePrefs", Context.MODE_PRIVATE); val currentBudget = prefs.getFloat("monthly_budget", 0f); if (currentBudget > 0) { etBudget.setText(currentBudget.toString()); etBudget.setSelection(etBudget.text.length) }; btnSaveBudget.setOnClickListener { val budget = etBudget.text.toString().toFloatOrNull() ?: 0f; prefs.edit().putFloat("monthly_budget", budget).apply(); updateBudgetUI(); Toast.makeText(this, "预算已更新", Toast.LENGTH_SHORT).show(); dialog.dismiss() }; dialog.show(); etBudget.requestFocus()
    }

    private fun updateBudgetUI() {
        val prefs = getSharedPreferences("ExpensePrefs", Context.MODE_PRIVATE); val budget = prefs.getFloat("monthly_budget", 0f); val currentMonth = currentDateStr.substring(0, 7); val db = dbHelper.readableDatabase; val cursor = db.rawQuery("SELECT SUM(amount) FROM records WHERE type='支出' AND date LIKE ?", arrayOf("$currentMonth%")); var monthExpense = 0f; if (cursor.moveToFirst()) monthExpense = cursor.getFloat(0); cursor.close(); val tvBudgetText = findViewById<TextView>(R.id.tvBudgetText); val pbBudget = findViewById<ProgressBar>(R.id.pbBudget); if (budget <= 0f) { tvBudgetText.text = String.format("已用 ¥%.2f / 未设置", monthExpense); pbBudget.progress = 0; pbBudget.progressTintList = ColorStateList.valueOf(Color.parseColor("#E0E0E0")) } else { tvBudgetText.text = String.format("已用 ¥%.2f / ¥%.0f", monthExpense, budget); val percent = ((monthExpense / budget) * 100).toInt(); val targetProgress = min(percent, 100); ObjectAnimator.ofInt(pbBudget, "progress", 0, targetProgress).apply { duration = 1000; interpolator = DecelerateInterpolator(); start() }; when { percent < 80 -> pbBudget.progressTintList = ColorStateList.valueOf(Color.parseColor("#43A047")); percent < 100 -> pbBudget.progressTintList = ColorStateList.valueOf(Color.parseColor("#FF9800")); else -> pbBudget.progressTintList = ColorStateList.valueOf(Color.parseColor("#E53935")) } }
    }

    private fun showStatisticsDialog() {
        val dialog = BottomSheetDialog(this); val view = LayoutInflater.from(this).inflate(R.layout.dialog_statistics, null); dialog.setContentView(view); dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT); val tvStatsTitle = view.findViewById<TextView>(R.id.tvStatsTitle); val tvTotalMonthExpense = view.findViewById<TextView>(R.id.tvTotalMonthExpense); val pieChartContainer = view.findViewById<FrameLayout>(R.id.pieChartContainer); val layoutLegend = view.findViewById<LinearLayout>(R.id.layoutLegend); val queryMonthSdf = SimpleDateFormat("yyyy-MM", Locale.getDefault()); val currentMonth = queryMonthSdf.format(currentCalendarMonth.time); tvStatsTitle.text = "${currentMonth.replace("-", "年")}月 支出占比"; val db = dbHelper.readableDatabase; val cursor = db.rawQuery("SELECT category, SUM(amount) FROM records WHERE type='支出' AND date LIKE ? GROUP BY category ORDER BY SUM(amount) DESC", arrayOf("$currentMonth%")); val categoryData = LinkedHashMap<String, Float>(); var totalMonth = 0f; while (cursor.moveToNext()) { val cat = cursor.getString(0); val sum = cursor.getFloat(1); categoryData[cat] = sum; totalMonth += sum }; cursor.close(); tvTotalMonthExpense.text = String.format("共支出 ¥ %.2f", totalMonth); val pieChartView = SimplePieChartView(this); pieChartView.setData(categoryData); pieChartContainer.addView(pieChartView); if (categoryData.isEmpty()) { val emptyText = TextView(this).apply { text = "本月暂无支出记录"; gravity = Gravity.CENTER; setPadding(0, 50, 0, 50) }; layoutLegend.addView(emptyText) } else { var colorIndex = 0; for ((category, amount) in categoryData) { val percent = if (totalMonth > 0) (amount / totalMonth) * 100 else 0f; val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16, 0, 16) }; val colorDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(30, 30).apply { marginEnd = 24 }; background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(pieChartView.getColor(colorIndex)) } }; val tvName = TextView(this).apply { text = category; textSize = 16f; setTextColor(Color.parseColor("#333333")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }; val tvPercent = TextView(this).apply { text = String.format("%.1f%%", percent); textSize = 14f; setTextColor(Color.parseColor("#888888")); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 24 } }; val tvAmt = TextView(this).apply { text = String.format("¥ %.2f", amount); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E53935")) }; row.addView(colorDot); row.addView(tvName); row.addView(tvPercent); row.addView(tvAmt); layoutLegend.addView(row); colorIndex++ } }; dialog.show()
    }

    private fun setupSwipeToDelete() { val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) { override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, tgt: RecyclerView.ViewHolder) = false; override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { val position = viewHolder.adapterPosition; val recordToDelete = recordList[position]; AlertDialog.Builder(this@MainActivity).setTitle("删除账单").setMessage("确定要删除这笔 ${recordToDelete.amount} 元的${recordToDelete.category}账单吗？").setPositiveButton("删除") { _, _ -> dbHelper.deleteRecord(recordToDelete.id); loadDataForDate(selectedDateStr); updateChartData(); updateCalendarUI() }.setNegativeButton("取消") { _, _ -> adapter.notifyItemChanged(position) }.setOnCancelListener { adapter.notifyItemChanged(position) }.show() } }); itemTouchHelper.attachToRecyclerView(recyclerView) }

    private fun showAddOrEditRecordDialog(existingRecord: Record?) {
        val dialog = BottomSheetDialog(this); val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_record, null); dialog.setContentView(view); dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT); val tvDialogTitle = view.findViewById<TextView>(R.id.tvDialogTitle); val rgType = view.findViewById<RadioGroup>(R.id.rgType); val rbExpense = view.findViewById<RadioButton>(R.id.rbExpense); val rbIncome = view.findViewById<RadioButton>(R.id.rbIncome); val spinnerCategory = view.findViewById<Spinner>(R.id.spinnerCategory); val etAmount = view.findViewById<EditText>(R.id.etAmount); val etNote = view.findViewById<EditText>(R.id.etNote); val btnSave = view.findViewById<Button>(R.id.btnSave); val expenseCategories = arrayOf("餐饮", "交通", "购物", "居住", "娱乐", "数码", "医疗", "其他"); val incomeCategories = arrayOf("工资", "兼职", "理财", "收租", "礼金", "奖金", "退款", "其他"); fun updateCategorySpinner(isExpense: Boolean) { spinnerCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, if (isExpense) expenseCategories else incomeCategories) }; fun getTabDrawable(colorStr: String): android.graphics.drawable.GradientDrawable { return android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor(colorStr)); cornerRadius = 100f } }; if (existingRecord == null) { tvDialogTitle.text = "记一笔 ($selectedDateStr)"; updateCategorySpinner(true); rbExpense.background = getTabDrawable("#FFCDD2"); rbExpense.setTextColor(Color.parseColor("#E53935")); btnSave.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935")) } else { tvDialogTitle.text = "修改账单"; etAmount.setText(existingRecord.amount.toString()); etNote.setText(existingRecord.note); if (existingRecord.type == "支出") { rbExpense.isChecked = true; updateCategorySpinner(true); rbExpense.background = getTabDrawable("#FFCDD2"); rbExpense.setTextColor(Color.parseColor("#E53935")); btnSave.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935")); spinnerCategory.setSelection(expenseCategories.indexOf(existingRecord.category)) } else { rbIncome.isChecked = true; updateCategorySpinner(false); rbIncome.background = getTabDrawable("#C8E6C9"); rbIncome.setTextColor(Color.parseColor("#43A047")); btnSave.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#43A047")); spinnerCategory.setSelection(incomeCategories.indexOf(existingRecord.category)) }; btnSave.text = "保存修改" }; rgType.setOnCheckedChangeListener { _, checkedId -> if (checkedId == R.id.rbExpense) { rbExpense.background = getTabDrawable("#FFCDD2"); rbExpense.setTextColor(Color.parseColor("#E53935")); rbIncome.background = null; rbIncome.setTextColor(Color.parseColor("#888888")); btnSave.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935")); updateCategorySpinner(true) } else { rbIncome.background = getTabDrawable("#C8E6C9"); rbIncome.setTextColor(Color.parseColor("#43A047")); rbExpense.background = null; rbExpense.setTextColor(Color.parseColor("#888888")); btnSave.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#43A047")); updateCategorySpinner(false) } }; btnSave.setOnClickListener { val amountStr = etAmount.text.toString(); if (amountStr.isNotEmpty()) { val type = if (rbExpense.isChecked) "支出" else "收入"; val category = spinnerCategory.selectedItem.toString(); val amount = amountStr.toDouble(); val note = etNote.text.toString(); if (existingRecord == null) { dbHelper.insertRecord(type, category, amount, note, "$selectedDateStr ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}") } else { dbHelper.updateRecord(existingRecord.id, type, category, amount, note, existingRecord.date) }; loadDataForDate(selectedDateStr); updateChartData(); updateCalendarUI(); dialog.dismiss() } }; dialog.show()
    }

    private fun loadDataForDate(datePrefix: String) {
        val displaySdf = SimpleDateFormat("MM月dd日", Locale.getDefault()); try { val dateObj = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(datePrefix); tvSelectedDate.text = "${displaySdf.format(dateObj)} 净收支" } catch (e: Exception) {}; recordList.clear(); adapter.resetAnimation(); var dailyExpense = 0.0; var dailyIncome = 0.0; val db = dbHelper.readableDatabase; val cursor = db.rawQuery("SELECT * FROM records WHERE date LIKE ? ORDER BY id DESC", arrayOf("$datePrefix%")); with(cursor) { while (moveToNext()) { val id = getInt(getColumnIndexOrThrow("id")); val type = getString(getColumnIndexOrThrow("type")); val category = getString(getColumnIndexOrThrow("category")); val amount = getDouble(getColumnIndexOrThrow("amount")); val note = getString(getColumnIndexOrThrow("note")); val date = getString(getColumnIndexOrThrow("date")); recordList.add(Record(id, type, category, amount, note, date)); if (type == "支出") dailyExpense += amount else dailyIncome += amount } }; cursor.close(); adapter.notifyDataSetChanged(); val netBalance = dailyIncome - dailyExpense; if (netBalance < 0) { tvDailyExpense.text = String.format("- ¥ %.2f", Math.abs(netBalance)); tvDailyExpense.setTextColor(Color.parseColor("#E53935")) } else if (netBalance > 0) { tvDailyExpense.text = String.format("+ ¥ %.2f", netBalance); tvDailyExpense.setTextColor(Color.parseColor("#43A047")) } else { tvDailyExpense.text = "¥ 0.00"; tvDailyExpense.setTextColor(Color.parseColor("#666666")) }; updateBudgetUI()
    }

    private fun updateChartData() {
        val db = dbHelper.readableDatabase; val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()); val last7DaysExpense = FloatArray(7); val calendarExpense = Calendar.getInstance(); for (i in 6 downTo 0) { calendarExpense.time = Date(); calendarExpense.add(Calendar.DAY_OF_YEAR, -i); val dayStr = sdf.format(calendarExpense.time); var dayTotal = 0.0; val cursor = db.rawQuery("SELECT amount FROM records WHERE type='支出' AND date LIKE ?", arrayOf("$dayStr%")); while (cursor.moveToNext()) { dayTotal += cursor.getDouble(0) }; cursor.close(); last7DaysExpense[6 - i] = dayTotal.toFloat() }; expenseChartView.setData(last7DaysExpense.toList()); val queryMonthSdf = SimpleDateFormat("yyyy-MM", Locale.getDefault()); val currentMonthStr = queryMonthSdf.format(currentCalendarMonth.time); val calendarMonthCalc = currentCalendarMonth.clone() as Calendar; val daysInMonth = calendarMonthCalc.getActualMaximum(Calendar.DAY_OF_MONTH); val thisMonthExpense = FloatArray(daysInMonth); for (day in 1..daysInMonth) { val dayStr = String.format("%s-%02d", currentMonthStr, day); var dayTotal = 0.0; val cursor = db.rawQuery("SELECT amount FROM records WHERE type='支出' AND date LIKE ?", arrayOf("$dayStr%")); while (cursor.moveToNext()) { dayTotal += cursor.getDouble(0) }; cursor.close(); thisMonthExpense[day - 1] = dayTotal.toFloat() }; incomeChartView.setData(thisMonthExpense.toList())
    }
}

// ------ 数据库操作 ------
class RecordDbHelper(context: Context) : SQLiteOpenHelper(context, "CommercialExpense.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, category TEXT, amount REAL, note TEXT, date TEXT)")
        db.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, room TEXT, dayOfWeek INTEGER, startSection INTEGER, endSection INTEGER, color TEXT, startWeek INTEGER, endWeek INTEGER, teacher TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            db.execSQL("DROP TABLE IF EXISTS courses")
            db.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, room TEXT, dayOfWeek INTEGER, startSection INTEGER, endSection INTEGER, color TEXT, startWeek INTEGER, endWeek INTEGER, teacher TEXT)")
        }
    }
    fun insertRecord(type: String, category: String, amount: Double, note: String, date: String) { val values = ContentValues().apply { put("type", type); put("category", category); put("amount", amount); put("note", note); put("date", date) }; writableDatabase.insert("records", null, values) }
    fun updateRecord(id: Int, type: String, category: String, amount: Double, note: String, date: String) { val values = ContentValues().apply { put("type", type); put("category", category); put("amount", amount); put("note", note); put("date", date) }; writableDatabase.update("records", values, "id=?", arrayOf(id.toString())) }
    fun deleteRecord(id: Int) { writableDatabase.delete("records", "id=?", arrayOf(id.toString())) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("MedicationPrefs", Context.MODE_PRIVATE); val timeStr = prefs.getString("reminder_time", "")
            if (!timeStr.isNullOrEmpty() && timeStr.contains(":")) { val parts = timeStr.split(":"); val hour = parts[0].toIntOrNull() ?: return; val minute = parts[1].toIntOrNull() ?: return; val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager; val alarmIntent = Intent(context, MedicationReceiver::class.java); val pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); if (before(Calendar.getInstance())) { add(Calendar.DAY_OF_MONTH, 1) } }; try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent) } else { alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent) } } catch (e: SecurityException) {} }
        }
    }
}
class MedicationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = "medication_channel"; val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(channelId, "健康提醒", NotificationManager.IMPORTANCE_HIGH).apply { description = "用于每日按时提醒记录" }; notificationManager.createNotificationChannel(channel) }; val mainIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }; val pendingIntent = PendingIntent.getActivity(context, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("到时间该记录了").setContentText("为了您的健康，请记得按时服用并打卡。").setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL).setContentIntent(pendingIntent).setAutoCancel(true).build(); notificationManager.notify(1001, notification); val prefs = context.getSharedPreferences("MedicationPrefs", Context.MODE_PRIVATE); val timeStr = prefs.getString("reminder_time", ""); if (!timeStr.isNullOrEmpty() && timeStr.contains(":")) { val parts = timeStr.split(":"); val hour = parts[0].toIntOrNull() ?: return; val minute = parts[1].toIntOrNull() ?: return; val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager; val nextIntent = Intent(context, MedicationReceiver::class.java); val nextPendingIntent = PendingIntent.getBroadcast(context, 0, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); add(Calendar.DAY_OF_MONTH, 1) }; try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, nextPendingIntent) } else { alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, nextPendingIntent) } } catch (e: SecurityException) {} }
    }
}
class MedCalendarAdapter(private val daysList: List<MedCalendarDay>, private val onDayClick: (MedCalendarDay) -> Unit) : RecyclerView.Adapter<MedCalendarAdapter.ViewHolder>() { class ViewHolder(view: View) : RecyclerView.ViewHolder(view) { val layoutDayCell: LinearLayout = view.findViewById(R.id.layoutDayCell); val tvDayNumber: TextView = view.findViewById(R.id.tvDayNumber); val tvDayBalance: TextView = view.findViewById(R.id.tvDayBalance) }; override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder { return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)) }; override fun onBindViewHolder(holder: ViewHolder, position: Int) { val dayInfo = daysList[position]; holder.tvDayBalance.visibility = View.GONE; if (!dayInfo.isCurrentMonth) { holder.tvDayNumber.text = ""; holder.layoutDayCell.setBackgroundColor(Color.TRANSPARENT); return }; holder.tvDayNumber.text = dayInfo.dayNumber.toString(); when (dayInfo.status) { 1 -> holder.tvDayNumber.setTextColor(Color.parseColor("#43A047")); -1 -> holder.tvDayNumber.setTextColor(Color.parseColor("#E53935")); else -> holder.tvDayNumber.setTextColor(Color.parseColor("#333333")) }; holder.itemView.setOnClickListener { onDayClick(dayInfo) } }; override fun getItemCount() = daysList.size }
class CalendarAdapter(private val daysList: List<CalendarDay>, private val onDayClick: (CalendarDay) -> Unit) : RecyclerView.Adapter<CalendarAdapter.ViewHolder>() { class ViewHolder(view: View) : RecyclerView.ViewHolder(view) { val layoutDayCell: LinearLayout = view.findViewById(R.id.layoutDayCell); val tvDayNumber: TextView = view.findViewById(R.id.tvDayNumber); val tvDayBalance: TextView = view.findViewById(R.id.tvDayBalance) }; override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder { return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)) }; override fun onBindViewHolder(holder: ViewHolder, position: Int) { val dayInfo = daysList[position]; if (!dayInfo.isCurrentMonth) { holder.tvDayNumber.text = ""; holder.tvDayBalance.visibility = View.INVISIBLE; holder.layoutDayCell.setBackgroundColor(Color.TRANSPARENT); return }; holder.tvDayNumber.text = dayInfo.dayNumber.toString(); if (dayInfo.isSelected) { holder.tvDayNumber.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#2196F3")) }; holder.tvDayNumber.setTextColor(Color.WHITE) } else { holder.tvDayNumber.background = null; holder.tvDayNumber.setTextColor(Color.parseColor("#333333")) }; if (dayInfo.netBalance > 0) { holder.tvDayBalance.visibility = View.VISIBLE; holder.tvDayBalance.text = "+${dayInfo.netBalance.toInt()}"; holder.tvDayBalance.setTextColor(Color.parseColor("#43A047")) } else if (dayInfo.netBalance < 0) { holder.tvDayBalance.visibility = View.VISIBLE; holder.tvDayBalance.text = "${dayInfo.netBalance.toInt()}"; holder.tvDayBalance.setTextColor(Color.parseColor("#E53935")) } else { holder.tvDayBalance.visibility = View.INVISIBLE }; holder.itemView.setOnClickListener { onDayClick(dayInfo) } }; override fun getItemCount() = daysList.size }
class RecordAdapter(private val recordList: List<Record>, private val onItemClick: (Record) -> Unit) : RecyclerView.Adapter<RecordAdapter.ViewHolder>() { private var lastAnimatedPosition = -1; fun resetAnimation() { lastAnimatedPosition = -1 }; class ViewHolder(view: View) : RecyclerView.ViewHolder(view) { val tvCategory: TextView = view.findViewById(R.id.tvCategory); val tvNote: TextView = view.findViewById(R.id.tvNote); val tvAmount: TextView = view.findViewById(R.id.tvAmount); val tvDate: TextView = view.findViewById(R.id.tvDate) }; override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder { return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)) }; override fun onBindViewHolder(holder: ViewHolder, position: Int) { val record = recordList[position]; holder.tvCategory.text = record.category; holder.tvNote.text = record.note.ifEmpty { "无备注" }; holder.tvDate.text = record.date.substring(11); if (record.type == "支出") { holder.tvAmount.text = "- ${record.amount}"; holder.tvAmount.setTextColor(Color.parseColor("#E53935")) } else { holder.tvAmount.text = "+ ${record.amount}"; holder.tvAmount.setTextColor(Color.parseColor("#43A047")) }; holder.itemView.setOnClickListener { onItemClick(record) }; if (position > lastAnimatedPosition) { holder.itemView.alpha = 0f; holder.itemView.translationY = 50f; holder.itemView.animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay((position * 30).toLong()).setInterpolator(DecelerateInterpolator()).start(); lastAnimatedPosition = position } }; override fun getItemCount() = recordList.size }
class SimplePieChartView(context: Context) : View(context) { private var data: Map<String, Float> = emptyMap(); private val colors = listOf(Color.parseColor("#FF6B6B"), Color.parseColor("#4ECDC4"), Color.parseColor("#FFD166"), Color.parseColor("#118AB2"), Color.parseColor("#06D6A0"), Color.parseColor("#EF476F"), Color.parseColor("#F78C6B"), Color.parseColor("#83D0C9")); private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }; private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }; private var animProgress = 1f; fun setData(data: Map<String, Float>) { this.data = data; ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; interpolator = DecelerateInterpolator(); addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }; start() } }; fun getColor(index: Int): Int = colors[index % colors.size]; override fun onDraw(canvas: Canvas) { super.onDraw(canvas); if (data.isEmpty() || data.values.sum() == 0f) return; val size = min(width, height).toFloat(); val rectF = RectF((width - size) / 2, (height - size) / 2, (width + size) / 2, (height + size) / 2); rectF.inset(20f, 20f); var startAngle = -90f; var colorIndex = 0; for ((_, value) in data) { val targetSweepAngle = (value / data.values.sum()) * 360f; val currentSweepAngle = targetSweepAngle * animProgress; paint.color = colors[colorIndex % colors.size]; canvas.drawArc(rectF, startAngle, currentSweepAngle, true, paint); startAngle += currentSweepAngle; colorIndex++ }; canvas.drawCircle(width / 2f, height / 2f, size / 3.5f, holePaint) } }
class SimpleLineChartView(context: Context) : View(context) { private var points: List<Float> = emptyList(); private var baseColor: Int = Color.parseColor("#2196F3"); private val linePaint = Paint().apply { strokeWidth = 5f; style = Paint.Style.STROKE; isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }; private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }; private val circleStrokePaint = Paint().apply { strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }; private val circleFillPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }; private var animProgress = 1f; fun setThemeColor(colorHex: String) { baseColor = Color.parseColor(colorHex); linePaint.color = baseColor; circleStrokePaint.color = baseColor; invalidate() }; fun setData(data: List<Float>) { this.points = data; ValueAnimator.ofFloat(0f, 1f).apply { duration = 1200; interpolator = DecelerateInterpolator(); addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }; start() } }; override fun onDraw(canvas: Canvas) { super.onDraw(canvas); if (points.isEmpty()) return; canvas.save(); canvas.clipRect(0f, 0f, width * animProgress, height.toFloat()); val padding = 16f; val drawWidth = width.toFloat() - padding * 2; val drawHeight = height.toFloat() - padding * 2; val maxPoint = max(points.maxOrNull() ?: 1f, 1f); val path = Path(); val fillPath = Path(); val stepX = drawWidth / (points.size - 1); val alphaColor = Color.argb(60, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)); fillPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), alphaColor, Color.TRANSPARENT, Shader.TileMode.CLAMP); points.forEachIndexed { index, value -> val x = padding + index * stepX; val y = padding + drawHeight - (value / maxPoint * drawHeight); if (index == 0) { path.moveTo(x, y); fillPath.moveTo(x, y) } else { path.lineTo(x, y); fillPath.lineTo(x, y) } }; fillPath.lineTo(padding + (points.size - 1) * stepX, height.toFloat()); fillPath.lineTo(padding, height.toFloat()); fillPath.close(); canvas.drawPath(fillPath, fillPaint); canvas.drawPath(path, linePaint); val radius = if (points.size > 20) 4f else 8f; points.forEachIndexed { index, value -> val x = padding + index * stepX; val y = padding + drawHeight - (value / maxPoint * drawHeight); canvas.drawCircle(x, y, radius, circleFillPaint); canvas.drawCircle(x, y, radius, circleStrokePaint) }; canvas.restore() } }