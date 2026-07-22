package io.github.daisukikaffuchino.han1meviewer.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.daisukikaffuchino.han1meviewer.Preferences
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.dao.CheckInRecordDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInRecordEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInType
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.EXTRA_OPEN_DAILY_CHECK_IN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class CheckInWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CheckInWidget()
}

class CheckInWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val stats = loadStats(context)
        val strings = CheckInWidgetStrings(
            title = context.getString(R.string.check_in_feature_name),
            today = context.getString(R.string.widget_today_label),
            todayCount = context.getString(R.string.counts, stats.todayCount),
            monthStats = context.getString(
                R.string.widget_month_stats_format,
                stats.checkedDays,
                stats.monthlyTotal,
            ),
            bestStreak = context.getString(R.string.widget_best_streak_format, stats.bestStreak),
            checkIn = context.getString(R.string.checkin),
            disabled = context.getString(R.string.widget_feature_disabled),
        )
        val enabled = Preferences.isCheckInEnabled
        val openCheckInAction = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_DAILY_CHECK_IN, true)
        )

        provideContent {
            CheckInWidgetContent(
                strings = strings,
                enabled = enabled,
                openCheckInAction = openCheckInAction,
            )
        }
    }
}

class CheckInWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        if (Preferences.isCheckInEnabled) {
            withContext(Dispatchers.IO) {
                val dao = CheckInRecordDatabase.getDatabase(context).checkInDao()
                val today = LocalDate.now().toString()
                if (dao.getCountByDate(today) < MAX_DAILY_CHECK_INS) {
                    dao.insert(
                        CheckInRecordEntity(
                            date = today,
                            time = LocalTime.now().format(TIME_FORMATTER),
                            type = CheckInType.MASTURBATION.storeName,
                            feeling = "",
                        )
                    )
                }
            }
        }
        CheckInWidget().updateAll(context)
    }
}

@Composable
private fun CheckInWidgetContent(
    strings: CheckInWidgetStrings,
    enabled: Boolean,
    openCheckInAction: Action,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_BACKGROUND)
            .cornerRadius(20.dp)
            .clickable(openCheckInAction)
            .padding(16.dp),
        horizontalAlignment = Alignment.Horizontal.Start,
        verticalAlignment = Alignment.Vertical.Top,
    ) {
        Text(
            text = strings.title,
            style = TextStyle(
                color = PRIMARY_COLOR,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = "${strings.today}  ${strings.todayCount}",
            style = TextStyle(
                color = PRIMARY_TEXT_COLOR,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = strings.monthStats,
            style = TextStyle(color = SECONDARY_TEXT_COLOR, fontSize = 13.sp),
        )
        Text(
            text = strings.bestStreak,
            style = TextStyle(color = SECONDARY_TEXT_COLOR, fontSize = 13.sp),
        )
        Spacer(modifier = GlanceModifier.height(12.dp))
        if (enabled) {
            Button(
                text = strings.checkIn,
                onClick = actionRunCallback<CheckInWidgetAction>(),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = strings.disabled,
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(color = SECONDARY_TEXT_COLOR, fontSize = 13.sp),
            )
        }
    }
}

private suspend fun loadStats(context: Context): CheckInWidgetStats = withContext(Dispatchers.IO) {
    val dao = CheckInRecordDatabase.getDatabase(context).checkInDao()
    val month = YearMonth.now()
    val monthPrefix = month.format(MONTH_FORMATTER)
    val records = dao.getRecordsBetween(
        month.atDay(1).toString(),
        month.atEndOfMonth().toString(),
    )
    val countsByDate = records.groupingBy { it.date }.eachCount()
    var currentStreak = 0
    var bestStreak = 0
    for (day in 1..month.lengthOfMonth()) {
        if ((countsByDate[month.atDay(day).toString()] ?: 0) > 0) {
            currentStreak++
            bestStreak = maxOf(bestStreak, currentStreak)
        } else {
            currentStreak = 0
        }
    }
    CheckInWidgetStats(
        todayCount = dao.getCountByDate(LocalDate.now().toString()),
        checkedDays = dao.getMonthlyCheckedDates(monthPrefix).size,
        monthlyTotal = records.size,
        bestStreak = bestStreak,
    )
}

private data class CheckInWidgetStats(
    val todayCount: Int,
    val checkedDays: Int,
    val monthlyTotal: Int,
    val bestStreak: Int,
)

private data class CheckInWidgetStrings(
    val title: String,
    val today: String,
    val todayCount: String,
    val monthStats: String,
    val bestStreak: String,
    val checkIn: String,
    val disabled: String,
)

private const val MAX_DAILY_CHECK_INS = 20
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM")
private val WIDGET_BACKGROUND = ColorProvider(Color(0xFFFFF8FA), Color(0xFF211A1C))
private val PRIMARY_COLOR = ColorProvider(Color(0xFF9C4160), Color(0xFFFFB1C5))
private val PRIMARY_TEXT_COLOR = ColorProvider(Color(0xFF25191D), Color(0xFFF2DFE4))
private val SECONDARY_TEXT_COLOR = ColorProvider(Color(0xFF62565A), Color(0xFFD0C2C6))
