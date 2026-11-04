package com.example.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    isGhostActive: Boolean,
    isDesktopSite: Boolean,
    onToggleDesktopSite: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenGhostDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0F19))
            .navigationBarsPadding()
            .height(52.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.size(44.dp).testTag("bottom_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) Color.White else Color(0xFF475569)
            )
        }

        IconButton(
            onClick = onForward,
            enabled = canGoForward,
            modifier = Modifier.size(44.dp).testTag("bottom_forward_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) Color.White else Color(0xFF475569)
            )
        }

        IconButton(
            onClick = onHome,
            modifier = Modifier.size(44.dp).testTag("bottom_home_button")
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home",
                tint = Color(0xFF38BDF8)
            )
        }

        IconButton(
            onClick = onOpenDownloads,
            modifier = Modifier.size(44.dp).testTag("bottom_downloads_button")
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Downloads Sniper",
                tint = Color(0xFFF59E0B)
            )
        }

        IconButton(
            onClick = onToggleDesktopSite,
            modifier = Modifier.size(44.dp).testTag("bottom_desktop_site_button")
        ) {
            Icon(
                imageVector = Icons.Default.DesktopWindows,
                contentDescription = if (isDesktopSite) "Switch to mobile site" else "Request desktop site",
                tint = if (isDesktopSite) Color(0xFF38BDF8) else Color(0xFF94A3B8)
            )
        }

        IconButton(
            onClick = onOpenTabs,
            modifier = Modifier.size(44.dp).testTag("bottom_tabs_button")
        ) {
            Icon(
                imageVector = Icons.Default.Tab,
                contentDescription = "Tabs",
                tint = Color(0xFFE2E8F0)
            )
        }

        IconButton(
            onClick = onOpenGhostDialog,
            modifier = Modifier.size(44.dp).testTag("bottom_ghost_button")
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Ghost Shield",
                tint = if (isGhostActive) Color(0xFFA78BFA) else Color(0xFF64748B)
            )
        }
    }
}
