package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

// Reached only via AboutScreen's hidden 7-tap gesture -- see that screen's own comment. A
// single code field, nothing else: no name/tier picker here, those are entirely admin-managed
// directly in Supabase (public.tier_roster). Success or failure both just report a plain
// result and let the user go back -- there is deliberately no visible indication anywhere else
// in the app that this screen exists or what a successful claim unlocks.
@Composable
fun TierClaimScreen(appPreferences: AppPreferences, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    // null = no attempt yet, true = last attempt succeeded, false = last attempt failed.
    var lastResult by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OBSERVER CODE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onBack) {
                    Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    lastResult = null
                },
                label = { Text("Code") },
                singleLine = true,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Button(
                enabled = code.isNotBlank() && !isSubmitting,
                onClick = {
                    isSubmitting = true
                    lastResult = null
                    scope.launch {
                        val subscriberId = appPreferences.getOrCreateSubscriberId()
                        val tier = SupabaseApi.redeemTierCode(code.trim(), subscriberId)
                        lastResult = tier != null
                        isSubmitting = false
                        if (tier != null) code = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text("SUBMIT", color = Color.Black, fontWeight = FontWeight.Black)
                }
            }

            // Deliberately generic wording either way -- a bad code and an already-used code
            // look identical here, same as redeem_tier_code's own null-for-every-failure
            // design, so this screen can't be used to probe which codes exist.
            lastResult?.let { success ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (success) "Code accepted." else "That code isn't valid.",
                    color = if (success) Color(0xFF00E5FF) else Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
