package com.cookinlet.belugas

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// The behavioral framing shown here (short) also lives on ResourcesScreen under "HOW TO USE
// THIS APP" (longer, explanatory) -- see that section's own comment for why the two versions
// differ in length rather than one being reused verbatim.
private const val ACKNOWLEDGEMENT_BODY_TEXT =
    "Respect private property when viewing or reporting sightings -- don't cross fences or " +
        "trespass for a better look. When you log a sighting, mark where the whale was, not " +
        "where you were standing -- that's what actually helps other observers and researchers."

/**
 * First-run gate -- shown once on install and again after any reinstall (see AppPreferences.
 * getHasAcknowledgedFirstRunGate/setHasAcknowledgedFirstRunGate's own comment on why the flag
 * deliberately doesn't survive Android Auto Backup). Plain and unsplashed on purpose: no
 * artwork, no icon reveal, nothing that could read as decorative rather than something to
 * actually read -- AppBackground(backgroundImageAlpha = 0f) gives the same flat backdrop the
 * splash screen starts on, without ever fading art in.
 *
 * [onAcknowledged] fires after the flag is persisted -- callers (App.kt's routing) should treat
 * it as "safe to proceed to the normal launch screen now," not before.
 */
@Composable
fun AcknowledgementGateScreen(appPreferences: AppPreferences, onAcknowledged: () -> Unit) {
    var isChecked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AppBackground(backgroundImageAlpha = 0f) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "BEFORE YOU START",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    ACKNOWLEDGEMENT_BODY_TEXT,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { isChecked = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00E5FF))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("I understand", color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            appPreferences.setHasAcknowledgedFirstRunGate(true)
                            onAcknowledged()
                        }
                    },
                    enabled = isChecked,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CONTINUE", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
