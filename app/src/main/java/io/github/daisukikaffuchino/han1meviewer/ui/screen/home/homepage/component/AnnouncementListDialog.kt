package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.han1meviewer.ui.component.lazy.LazyColumn
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeAnnouncements

/**
 * 显示完整公告列表弹窗。
 *
 * @param announcements 可供选择的公告列表。
 * @param onDismiss 关闭弹窗时调用。
 */
@Composable
fun AnnouncementListDialog(
    announcements: List<Announcement>,
    onDismiss: () -> Unit
) {
    var selectedAnnouncement by remember { mutableStateOf<Announcement?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.announcement_list),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(announcements.size) { index ->
                    val item = announcements[index]
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAnnouncement = item }
                                .padding(12.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                stringResource(R.string.close),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(8.dp)
            )
        }
    )

    selectedAnnouncement?.let { announcement ->
        AnnouncementDialog(
            announcementData = announcement,
            onDismiss = { selectedAnnouncement = null }
        )
    }
}

@Preview(showBackground = true, name = "公告列表弹窗", showSystemUi = true)
@Composable
private fun AnnouncementListDialogPreview() {
    ComponentPreview {
        AnnouncementListDialog(
            announcements = fakeAnnouncements,
            onDismiss = {}
        )
    }
}
