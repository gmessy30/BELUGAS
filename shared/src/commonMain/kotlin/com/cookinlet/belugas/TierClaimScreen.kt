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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Groups a raw 8-character Crockford short code for display/reading aloud, e.g. "K7X92FQR" ->
// "K7X9-2FQR". Stored ungrouped server-side (subscriber_identities.short_code) -- the dash is
// purely a display convenience, never part of the value itself, so admin_bind_tier_code strips
// non-alphanumeric characters before matching regardless of whether it's typed with or without it.
private fun groupedShortCode(code: String): String =
    code.chunked(4).joinToString("-")

// Reached only via AboutScreen's hidden 7-tap gesture -- see that screen's own comment. A code
// field, plus (below it) this device's own short id for the manual fallback: someone who can't
// manage the code entry can read that id aloud over the phone instead, and an admin binds a
// code to it by hand via admin_bind_tier_code
// (20260905010000_add_tier_code_rate_limiting_and_identity_bind.sql). No name/tier picker here
// either way -- those stay entirely admin-managed directly in Supabase (public.tier_roster).
@Composable
fun TierClaimScreen(appPreferences: AppPreferences, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    // null = no attempt yet.
    var lastResult by remember { mutableStateOf<TierRedeemResult?>(null) }
    val scope = rememberCoroutineScope()

    // Fetched once when this screen loads -- which, since it's only reachable through the
    // hidden gesture, already IS "the fallback panel being revealed." getOrCreateSubscriberId
    // is cheap/local; getOrCreateSubscriberIdentity is the one network round trip, and mints
    // this device's short code server-side on its first-ever call (see that RPC's own comment).
    var subscriberId by remember { mutableStateOf<String?>(null) }
    var shortCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val id = appPreferences.getOrCreateSubscriberId()
        subscriberId = id
        shortCode = SupabaseApi.getOrCreateSubscriberIdentity(id)
    }

    val clipboardManager = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

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
                enabled = code.isNotBlank() && !isSubmitting && subscriberId != null,
                onClick = {
                    val id = subscriberId ?: return@Button
                    isSubmitting = true
                    lastResult = null
                    scope.launch {
                        val result = SupabaseApi.redeemTierCode(code.trim(), id)
                        lastResult = result
                        isSubmitting = false
                        if (result is TierRedeemResult.Success) code = ""
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

            // Deliberately generic wording for Invalid -- a bad code and an already-used code
            // look identical here, same as redeem_tier_code's own null-for-every-failure
            // design, so this screen can't be used to probe which codes exist. RateLimited gets
            // its own distinct, honest message -- it says nothing about whether any code tried
            // was valid, only that this device should slow down, so surfacing it separately
            // doesn't reopen that concern (see TierRedeemResult's own comment).
            lastResult?.let { result ->
                Spacer(Modifier.height(16.dp))
                val (text, color) = when (result) {
                    is TierRedeemResult.Success -> "Code accepted." to Color(0xFF00E5FF)
                    is TierRedeemResult.Invalid -> "That code isn't valid." to Color(0xFFFF5252)
                    is TierRedeemResult.RateLimited -> "Too many attempts -- try again in a bit." to Color(0xFFFF9800)
                }
                Text(text = text, color = color, fontWeight = FontWeight.Bold)
            }

            // Manual fallback: this device's own short id, for someone who can't manage the
            // code entry above to read aloud over the phone instead. Only shown once fetched --
            // no placeholder/loading text, since a failed fetch here shouldn't read as an error
            // on top of whatever the code-entry flow is doing.
            shortCode?.let { rawCode ->
                Spacer(Modifier.height(40.dp))
                Text(
                    "CAN'T ENTER A CODE?",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Read this to an admin over the phone -- they can bind your code to it directly.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        groupedShortCode(rawCode),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(rawCode))
                        justCopied = true
                    }) {
                        Text(
                            if (justCopied) "COPIED" else "COPY",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
