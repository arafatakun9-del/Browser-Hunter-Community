package com.example.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
fun BrowserOmnibox(
    currentUrl: String,
    isLoading: Boolean,
    progress: Int,
    isPdfActive: Boolean,
    isDarkMode: Boolean,
    isBookmarked: Boolean,
    tabCount: Int,
    isGhostActive: Boolean,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenBookmarksHistory: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onTranslateBengali: () -> Unit,
    onToggleReaderMode: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenGhostDialog: () -> Unit,
    onNewTab: () -> Unit,
    onExitBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf(currentUrl) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentUrl) {
        if (!isEditing) {
            textInput = currentUrl
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Address Bar Box
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("omnibox_surface"),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF1E293B),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isPdfActive) Color(0xFF10B981) else if (isGhostActive) Color(0xFF8B5CF6) else Color(0xFF334155)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Security or PDF icon
                    if (isPdfActive) {
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = "PDF Document",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "PDF Clean",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "SSL Secure",
                            tint = if (isGhostActive) Color(0xFFA78BFA) else Color(0xFF10B981),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Input Field
                    BasicTextField(
                        value = textInput,
                        onValueChange = {
                            textInput = it
                            isEditing = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("omnibox_input"),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(Color(0xFF38BDF8)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            isEditing = false
                            onNavigate(textInput)
                        }),
                        decorationBox = { innerTextField ->
                            if (textInput.isEmpty()) {
                                Text(
                                    text = "Search or web address…",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    // Bookmark Quick Action
                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                        IconButton(
                            onClick = onToggleBookmark,
                            modifier = Modifier.size(28.dp).testTag("omnibox_bookmark_button")
                        ) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Bookmark",
                                tint = if (isBookmarked) Color(0xFFFBBF24) else Color(0xFF64748B),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Clear / Refresh icon
                    if (isEditing && textInput.isNotEmpty()) {
                        IconButton(
                            onClick = { textInput = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Quick Actions:
            // 1. Bengali Translate
            IconButton(
                onClick = onTranslateBengali,
                modifier = Modifier.size(34.dp).testTag("translate_bn_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Translate to Bengali",
                    tint = Color(0xFF06B6D4),
                    modifier = Modifier.size(18.dp)
                )
            }

            // 2. Reader Mode
            IconButton(
                onClick = onToggleReaderMode,
                modifier = Modifier.size(34.dp).testTag("reader_mode_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "Reader Mode",
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(18.dp)
                )
            }

            // 3. Dark Mode Toggle
            IconButton(
                onClick = onToggleDarkMode,
                modifier = Modifier.size(34.dp).testTag("dark_mode_button")
            ) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle Dark Mode",
                    tint = if (isDarkMode) Color(0xFFFDE047) else Color(0xFF94A3B8),
                    modifier = Modifier.size(18.dp)
                )
            }

            // 4. Arafat AI Button with user's portrait avatar!
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color(0xFF06B6D4), CircleShape)
                    .clickable(onClick = onOpenAi)
                    .testTag("arafat_ai_omnibox_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.arafat_icon_fg),
                    contentDescription = "Arafat AI",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 5. Tabs Count Badge
            Surface(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenTabs)
                    .testTag("tabs_badge_button"),
                color = Color(0xFF1E293B),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = tabCount.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 6. Overflow Menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(34.dp).testTag("overflow_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    DropdownMenuItem(
                        text = { Text("New Tab", color = Color.White) },
                        onClick = {
                            showMenu = false
                            onNewTab()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks & History", color = Color(0xFFFBBF24)) },
                        onClick = {
                            showMenu = false
                            onOpenBookmarksHistory()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sniper Downloader", color = Color(0xFFF59E0B)) },
                        onClick = {
                            showMenu = false
                            onOpenDownloads()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ghost Shield & Security", color = Color(0xFFA78BFA)) },
                        onClick = {
                            showMenu = false
                            onOpenGhostDialog()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Translate to বাংলা", color = Color(0xFF38BDF8)) },
                        onClick = {
                            showMenu = false
                            onTranslateBengali()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Zero-RAM Exit", color = Color(0xFFEF4444)) },
                        onClick = {
                            showMenu = false
                            onExitBrowser()
                        }
                    )
                }
            }
        }

        // Loading Progress Indicator
        AnimatedVisibility(visible = isLoading && progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = Color(0xFF06B6D4),
                trackColor = Color(0xFF0F172A)
            )
        }
    }
}
