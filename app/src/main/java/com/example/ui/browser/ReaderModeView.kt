package com.example.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ReaderArticle

enum class ReaderTheme(val bgColor: Color, val textColor: Color, val label: String) {
    DARK(Color(0xFF121212), Color(0xFFE0E0E0), "Dark"),
    SEPIA(Color(0xFFFBF0D9), Color(0xFF5F4B32), "Sepia"),
    LIGHT(Color(0xFFFFFFFF), Color(0xFF1E293B), "Light")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderModeView(
    article: ReaderArticle?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var readerTheme by remember { mutableStateOf(ReaderTheme.DARK) }
    var fontScale by remember { mutableFloatStateOf(16f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = readerTheme.bgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Reader Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Reader Mode",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = readerTheme.textColor
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    // Text size controls
                    Row(
                        modifier = Modifier
                            .background(readerTheme.textColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "A-",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = readerTheme.textColor,
                            modifier = Modifier
                                .clickable { if (fontScale > 13f) fontScale -= 2f }
                                .padding(horizontal = 6.dp)
                        )
                        Text(
                            text = "A+",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = readerTheme.textColor,
                            modifier = Modifier
                                .clickable { if (fontScale < 24f) fontScale += 2f }
                                .padding(horizontal = 6.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Theme toggles
                    ReaderTheme.values().forEach { t ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(t.bgColor, CircleShape)
                                .clickable { readerTheme = t }
                                .padding(2.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = readerTheme.textColor)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (article == null || article.paragraphs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Extracting article text or page has no article content…",
                        color = readerTheme.textColor.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = article.title,
                            fontSize = (fontScale + 6).sp,
                            fontWeight = FontWeight.Bold,
                            color = readerTheme.textColor,
                            lineHeight = (fontScale + 12).sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = article.domain,
                            fontSize = 11.sp,
                            color = readerTheme.textColor.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    items(article.paragraphs) { paragraph ->
                        Text(
                            text = paragraph,
                            fontSize = fontScale.sp,
                            color = readerTheme.textColor,
                            lineHeight = (fontScale * 1.55f).sp,
                            fontFamily = FontFamily.Serif
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
        }
    }
}
