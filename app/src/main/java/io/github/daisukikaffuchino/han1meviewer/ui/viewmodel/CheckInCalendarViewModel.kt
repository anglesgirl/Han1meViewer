package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import io.github.daisukikaffuchino.han1meviewer.logic.dao.CheckInRecordDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInRecordEntity
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.dailycheckin.DailyCheckInUiState
import io.github.daisukikaffuchino.han1meviewer.ui.widget.CheckInWidget
import io.github.daisukikaffuchino.utils.application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class CheckInCalendarViewModel : ViewModel() {

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    private val _records = MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
    private val _checkedDays = MutableStateFlow(0)
    private val _monthTotal = MutableStateFlow(0)
    private val _yearRecords = MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
    private val _monthRecords = MutableStateFlow<List<CheckInRecordEntity>>(emptyList())
    private val _monthlyStats = MutableStateFlow(MonthlyStats())
    private val _yearStats = MutableStateFlow(MonthlyStats())

    /** 对外暴露的唯一 UI 状态流。 */
    val uiState: StateFlow<DailyCheckInUiState> = combine(
        _currentMonth, _records, _checkedDays, _monthTotal, _monthlyStats,
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        val recordsMap = array[1] as Map<LocalDate, Int>
        val month = array[0] as YearMonth
        val checkedDaysVal = array[2] as Int
        val monthlyTotalVal = array[3] as Int
        val statsVal = array[4] as MonthlyStats
        val today = LocalDate.now()
        var bestStreak = 0
        var streak = 0
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            if ((recordsMap[date] ?: 0) > 0) {
                streak++
                if (streak > bestStreak) bestStreak = streak
            } else {
                streak = 0
            }
        }
        DailyCheckInUiState(
            currentMonth = month,
            records = recordsMap,
            checkedDays = checkedDaysVal,
            monthlyTotal = monthlyTotalVal,
            bestStreakThisMonth = bestStreak,
            monthlyStats = statsVal,
            today = today,
            todayCount = recordsMap[today] ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyCheckInUiState())

    val yearRecords: StateFlow<Map<LocalDate, Int>> = _yearRecords.asStateFlow()
    val yearStats: StateFlow<MonthlyStats> = _yearStats.asStateFlow()

    private val dao = CheckInRecordDatabase.getDatabase(application).checkInDao()

    init {
        loadMonthRecords(_currentMonth.value)
    }

    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
        loadMonthRecords(_currentMonth.value)
    }

    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
        loadMonthRecords(_currentMonth.value)
    }

    fun addRecord(
        date: LocalDate,
        time: String,
        type: String,
        feeling: String
    ) {
        viewModelScope.launch {
            val record = CheckInRecordEntity(
                date = date.toString(),
                time = time,
                type = type,
                feeling = feeling
            )
            dao.insert(record)
            reloadDateAndStats(date)
            updateWidget()
        }
    }

    fun deleteRecord(record: CheckInRecordEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            dao.delete(record)
            val date = LocalDate.parse(record.date, DateTimeFormatter.ISO_LOCAL_DATE)
            reloadDateAndStats(date)
            updateWidget()
            onDone()
        }
    }

    fun getRecordsByDate(date: LocalDate, onResult: (List<CheckInRecordEntity>) -> Unit) {
        viewModelScope.launch {
            val result = dao.getRecordsByDate(date.toString())
            onResult(result)
        }
    }

    fun getCountByDate(date: LocalDate, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = dao.getCountByDate(date.toString())
            onResult(count)
        }
    }

    fun clearCheckIn(date: LocalDate) {
        viewModelScope.launch {
            val records = dao.getRecordsByDate(date.toString())
            records.forEach { dao.delete(it) }
            reloadDateAndStats(date)
            updateWidget()
        }
    }

    fun loadYearRecords(year: Int) {
        viewModelScope.launch {
            val allRecords = dao.getYearlyRecords(year.toString())
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val countMap = mutableMapOf<LocalDate, Int>()
            allRecords.forEach {
                val localDate = LocalDate.parse(it.date, formatter)
                countMap[localDate] = (countMap[localDate] ?: 0) + 1
            }
            _yearRecords.value = countMap
            _yearStats.value = computeStats(allRecords)
        }
    }

    private suspend fun reloadDateAndStats(date: LocalDate) {
        val count = dao.getCountByDate(date.toString())
        _records.value = _records.value.toMutableMap().apply { this[date] = count }
        val month = _currentMonth.value
        val dates =
            dao.getMonthlyCheckedDates(month.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        _checkedDays.value = dates.size
        val totalCheckIns =
            dao.getMonthlyCheckInTotal(month.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        _monthTotal.value = totalCheckIns
        val allRecords =
            dao.getRecordsBetween(month.atDay(1).toString(), month.atEndOfMonth().toString())
        _monthRecords.value = allRecords
        _monthlyStats.value = computeStats(allRecords)
    }

    private fun loadMonthRecords(month: YearMonth) {
        viewModelScope.launch {
            val start = month.atDay(1)
            val end = month.atEndOfMonth()
            val allRecords = dao.getRecordsBetween(start.toString(), end.toString())
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            _monthRecords.value = allRecords
            val countMap = mutableMapOf<LocalDate, Int>()
            allRecords.forEach {
                val localDate = LocalDate.parse(it.date, formatter)
                countMap[localDate] = (countMap[localDate] ?: 0) + 1
            }
            _records.value = countMap
            _checkedDays.value = countMap.keys.size
            _monthTotal.value = countMap.values.sum()
            _monthlyStats.value = computeStats(allRecords)
        }
    }

    private suspend fun updateWidget() {
        runCatching { CheckInWidget().updateAll(application) }
    }

    companion object {
        fun computeStats(records: List<CheckInRecordEntity>): MonthlyStats {
            if (records.isEmpty()) return MonthlyStats()
            val typeCounts = records.groupingBy { it.type }.eachCount()
            val morning = records.count {
                val h = it.time.substringBefore(":").toIntOrNull() ?: 0; h in 5..10
            }
            val night = records.count {
                val h = it.time.substringBefore(":").toIntOrNull() ?: 0; h in 22..23 || h in 0..2
            }
            val afternoon = records.count {
                val h = it.time.substringBefore(":").toIntOrNull() ?: 0; h in 12..16
            }
            val totalFeelingChars = records.sumOf { it.feeling.length }
            val maxDailyTypes = records.groupBy { it.date }.values.maxOfOrNull {
                it.map { r -> r.type }.distinct().size
            } ?: 0
            val daysChecked = records.map { it.date }.distinct().size
            val bestStreak = run {
                val dates = records.map { it.date }.distinct().sorted()
                    .map { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
                var streak = 0
                var best = 0
                var prev: LocalDate? = null
                for (d in dates) {
                    if (prev != null && d == prev.plusDays(1)) streak++ else streak = 1
                    if (streak > best) best = streak
                    prev = d
                }
                best
            }

            val dominantPeriod = when {
                night > morning && night > afternoon -> "22~02"
                morning > night && morning > afternoon -> "05~10"
                afternoon > morning && afternoon > night -> "12~16"
                else -> ""
            }

            var scholarDate = ""
            if (totalFeelingChars >= 100) {
                var sum = 0
                for (r in records.sortedBy { it.date + it.time }) {
                    sum += r.feeling.length
                    if (sum >= 100) {
                        scholarDate = r.date.substring(5).replace("-", "/")
                        break
                    }
                }
            }

            return MonthlyStats(
                totalCount = records.size,
                daysChecked = daysChecked,
                bestStreak = bestStreak,
                typeCounts = typeCounts,
                morningCount = morning,
                nightCount = night,
                afternoonCount = afternoon,
                totalFeelingChars = totalFeelingChars,
                avgFeelingChars = if (records.isNotEmpty()) totalFeelingChars / records.size else 0,
                maxDailyTypes = maxDailyTypes,
                dominantPeriod = dominantPeriod,
                scholarDate = scholarDate,
            )
        }
    }
}

data class MonthlyStats(
    val totalCount: Int = 0,
    val daysChecked: Int = 0,
    val bestStreak: Int = 0,
    val typeCounts: Map<String, Int> = emptyMap(),
    val morningCount: Int = 0,
    val nightCount: Int = 0,
    val afternoonCount: Int = 0,
    val totalFeelingChars: Int = 0,
    val avgFeelingChars: Int = 0,
    val maxDailyTypes: Int = 0,
    val dominantPeriod: String = "",
    val scholarDate: String = ""
)
