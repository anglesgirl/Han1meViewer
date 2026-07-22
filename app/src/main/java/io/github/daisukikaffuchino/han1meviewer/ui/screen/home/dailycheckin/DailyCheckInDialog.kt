package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.dailycheckin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInRecordEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 打卡弹窗。展示历史记录、添加新记录的表单。
 *
 *
 * @param date 打卡日期
 * @param onLoadRecords 加载该日期已有记录的回调
 * @param onGetCountByDate 查询该日期打卡次数的回调
 * @param onAddRecord 新增打卡记录的回调
 * @param onDeleteRecord 删除单条记录的回调
 * @param onEasterEgg 触发彩蛋文字的回调
 * @param onDismiss 关闭弹窗的回调
 */
@Composable
fun CheckInDialog(
    date: LocalDate,
    onLoadRecords: (LocalDate, (List<CheckInRecordEntity>) -> Unit) -> Unit,
    onGetCountByDate: (LocalDate, (Int) -> Unit) -> Unit,
    onAddRecord: (LocalDate, String, String, String) -> Unit,
    onDeleteRecord: (CheckInRecordEntity, () -> Unit) -> Unit,
    onEasterEgg: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var existingRecords by remember { mutableStateOf<List<CheckInRecordEntity>>(emptyList()) }
    var todayCount by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(date) {
        onLoadRecords(date) { records ->
            existingRecords = records
        }
        onGetCountByDate(date) { todayCount = it; loaded = true }
    }

    if (!loaded) return

    val canAddMore = todayCount < 20

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f),
            //.fillMaxSize(0.85f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = date.format(DateTimeFormatter.ofPattern("yyyy\u5E74MM\u6708dd\u65E5")),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(painter = painterResource(R.drawable.close_24px), "close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                if (existingRecords.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.dialog_existing_records),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    existingRecords.forEachIndexed { index, record ->
                        ExistingRecordItem(
                            index = index + 1,
                            record = record,
                            onDelete = {
                                onDeleteRecord(record) {
                                    onLoadRecords(date) { records ->
                                        existingRecords = records
                                    }
                                    onGetCountByDate(date) { todayCount = it }
                                }
                            }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        )
                    )
                }

                if (canAddMore) {
                    val eggSex = stringResource(R.string.egg_three)
                    val eggNine = stringResource(R.string.egg_four)
                    val eggGod = stringResource(R.string.egg_god, 6)
                    val eggRoundTemplate = stringResource(R.string.egg_round)
                    AddCheckInForm(
                        onAddRecord = { time, type, feeling ->
                            onAddRecord(date, time, type, feeling)
                            onGetCountByDate(date) { newCount ->
                                when {
                                    newCount + 1 == 3 -> onEasterEgg(eggSex)
                                    newCount + 1 == 4 -> onEasterEgg(eggNine)
                                    newCount + 1 == 6 -> onEasterEgg(eggGod)
                                    newCount + 1 % 10 == 0 -> onEasterEgg(
                                        eggRoundTemplate.format(
                                            newCount
                                        )
                                    )
                                }
                                onDismiss()
                            }
                        },
                        onDismiss = onDismiss,
                    )
                } else if (todayCount >= 20) {
                    Text(
                        text = stringResource(R.string.dialog_max_reached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 新增打卡表单。
 *
 * @param onAddRecord 提交打卡记录的回调 (time, type, feeling)
 * @param onDismiss 取消/关闭回调
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCheckInForm(
    onAddRecord: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(CheckInType.MASTURBATION) }
    var feeling by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.dialog_type_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CheckInType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(stringResource(type.displayNameRes)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.dialog_feeling_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = feeling,
            onValueChange = { feeling = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            placeholder = { Text(stringResource(R.string.dialog_feeling_hint)) },
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val now = LocalTime.now()
                    onAddRecord(
                        now.format(DateTimeFormatter.ofPattern("HH:mm")),
                        selectedType.storeName,
                        feeling
                    )
                }
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 已有打卡记录项。展示单条打卡的类型、时间和感想。
 *
 * @param index 序号
 * @param record 打卡记录实体
 * @param onDelete 删除此记录的回调
 */
@Composable
fun ExistingRecordItem(
    index: Int,
    record: CheckInRecordEntity,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "$index",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(CheckInType.fromDisplayName(record.type).displayNameRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    if (record.time.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = record.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_delete_24),
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            if (record.feeling.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = record.feeling,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
