package com.example.ui.browser

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.UserAgentOption

@Composable
fun GhostModeDialog(
    isGhostActive: Boolean,
    isFlagSecure: Boolean,
    isShakeEnabled: Boolean,
    selectedUserAgent: UserAgentOption,
    isPinProtected: Boolean,
    onToggleGhost: (Boolean) -> Unit,
    onToggleFlagSecure: (Boolean) -> Unit,
    onToggleShake: (Boolean) -> Unit,
    onSelectUserAgent: (UserAgentOption) -> Unit,
    onSetPin: (String) -> Unit,
    onPurgeData: () -> Unit,
    onDismiss: () -> Unit
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set Ghost Mode PIN", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Set a 4-digit PIN to lock access to Ghost Mode tabs.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4) pinInput = it },
                        label = { Text("4-digit PIN") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFA78BFA),
                            unfocusedBorderColor = Color(0xFF475569)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput.length == 4) {
                            onSetPin(pinInput)
                            showPinDialog = false
                            Toast.makeText(context, "Ghost Mode PIN Set", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFFA78BFA),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ghost Shield & Privacy",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Ghost Mode Switch
                SecuritySettingItem(
                    title = "Ghost Mode (Incognito)",
                    subtitle = "Zero cookies, no browsing history recorded",
                    icon = Icons.Default.VpnLock,
                    iconColor = Color(0xFFA78BFA),
                    checked = isGhostActive,
                    onCheckedChange = onToggleGhost
                )

                // FLAG_SECURE Switch
                SecuritySettingItem(
                    title = "Obscure Recent Apps Preview",
                    subtitle = "Blocks screenshots and preview thumbnails via FLAG_SECURE",
                    icon = Icons.Default.ScreenLockPortrait,
                    iconColor = Color(0xFF38BDF8),
                    checked = isFlagSecure,
                    onCheckedChange = onToggleFlagSecure
                )

                // Shake to Clear Switch
                SecuritySettingItem(
                    title = "Shake to Clear Data",
                    subtitle = "Vigorously shake phone to instantly wipe history & cache",
                    icon = Icons.Default.Vibration,
                    iconColor = Color(0xFFF59E0B),
                    checked = isShakeEnabled,
                    onCheckedChange = onToggleShake
                )

                // User-Agent Spoofing
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Spoof User-Agent (Anti-Tracking)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E8F0)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        UserAgentOption.values().forEach { ua ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectUserAgent(ua) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(14.dp),
                                    shape = RoundedCornerShape(7.dp),
                                    color = if (ua == selectedUserAgent) Color(0xFFA78BFA) else Color.Transparent,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.5.dp,
                                        if (ua == selectedUserAgent) Color(0xFFA78BFA) else Color(0xFF64748B)
                                    )
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = ua.label,
                                    fontSize = 11.sp,
                                    color = if (ua == selectedUserAgent) Color.White else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }

                // Biometric / PIN Lock Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPinDialog = true }
                        .background(Color(0xFF131D2E), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Biometric & PIN Lock", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(if (isPinProtected) "PIN Protected" else "Not set", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                    }
                    Text(if (isPinProtected) "Change" else "Set PIN", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }

                // Immediate Data Purge Button
                Button(
                    onClick = {
                        onPurgeData()
                        onDismiss()
                        Toast.makeText(context, "All history, cookies & cache purged!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("purge_data_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nuke All Browsing Data Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFF38BDF8))
            }
        },
        containerColor = Color(0xFF1E293B)
    )
}

@Composable
fun SecuritySettingItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(text = subtitle, fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = iconColor,
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = Color(0xFF334155)
            )
        )
    }
}
