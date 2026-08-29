package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Stage 1 of the notification-zone subscriptions feature: browse Cook Inlet's seeded zones and
// create/delete a kind='zone' subscription watching one. Custom-polygon and point+radius
// subscriptions, editing, expires_at, is_active toggling, and actual push dispatch are all
// later stages -- this screen is purely management of what already exists.
@Composable
fun SubscriptionsScreen(onBack: () -> Unit, appPreferences: AppPreferences) {
    // Region picker deliberately omitted for this stage -- St. Lawrence has no seeded zone
    // data yet, so a picker would just show an empty zone list there. Hardcoded here rather
    // than left as a TODO so it's an explicit, visible decision.
    val region = Regions.COOK_INLET

    var subscriberId by remember { mutableStateOf<String?>(null) }
    var zones by remember { mutableStateOf<List<ZoneRecord>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<SubscriptionRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedZoneSlug by remember { mutableStateOf<String?>(null) }
    var selectedConfidenceFilter by remember { mutableStateOf(SubscriptionConfidenceFilter.ALL) }
    var isSubscribing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    suspend fun refreshSubscriptions(id: String) {
        subscriptions = SupabaseApi.getSubscriptions(id)
    }

    LaunchedEffect(Unit) {
        val id = appPreferences.getOrCreateSubscriberId()
        subscriberId = id
        zones = SupabaseApi.getZones(region.id)
        refreshSubscriptions(id)
        isLoading = false
    }

    fun subscribe() {
        val id = subscriberId ?: return
        val zone = zones.find { it.slug == selectedZoneSlug } ?: return
        if (isSubscribing) return
        scope.launch {
            isSubscribing = true
            actionError = null
            val success = SupabaseApi.createZoneSubscription(id, zone.id, selectedConfidenceFilter)
            if (success) {
                selectedZoneSlug = null
                refreshSubscriptions(id)
            } else {
                actionError = "Couldn't create that subscription. Try again."
            }
            isSubscribing = false
        }
    }

    fun unsubscribe(subscriptionId: String) {
        val id = subscriberId ?: return
        scope.launch {
            actionError = null
            val success = SupabaseApi.deleteSubscription(subscriptionId)
            if (success) {
                refreshSubscriptions(id)
            } else {
                actionError = "Couldn't remove that subscription. Try again."
            }
        }
    }

    AppBackground {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ALERTS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            TextButton(onClick = onBack) {
                Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Yellow)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SubscriptionSection(title = "WATCHING") {
                    if (subscriptions.isEmpty()) {
                        Text(
                            "Not watching any zones yet — subscribe to one below to get notified about sightings there.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            subscriptions.forEach { subscription ->
                                SubscriptionListItem(
                                    zoneName = zones.find { it.id == subscription.zoneId }?.name ?: "Unknown zone",
                                    confidenceLabel = SubscriptionConfidenceFilter.entries
                                        .find { it.dbValue == subscription.confidenceFilter }?.label
                                        ?: subscription.confidenceFilter,
                                    onRemove = { unsubscribe(subscription.id) }
                                )
                            }
                        }
                    }
                }

                SubscriptionSection(title = "ZONE") {
                    if (zones.isEmpty()) {
                        Text(
                            "No named zones for ${region.name} yet.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            zones.forEach { zone ->
                                FilterChip(
                                    selected = selectedZoneSlug == zone.slug,
                                    onClick = { selectedZoneSlug = zone.slug },
                                    label = { Text(zone.name, fontSize = 12.sp) },
                                    colors = subscriptionChipColors()
                                )
                            }
                        }
                    }
                }

                SubscriptionSection(title = "NOTIFY ME FOR") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubscriptionConfidenceFilter.entries.forEach { option ->
                            FilterChip(
                                selected = selectedConfidenceFilter == option,
                                onClick = { selectedConfidenceFilter = option },
                                label = { Text(option.label, fontSize = 12.sp) },
                                colors = subscriptionChipColors()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { subscribe() },
                    enabled = selectedZoneSlug != null && !isSubscribing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSubscribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("SUBSCRIBE", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }

                actionError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF5252), fontSize = 12.sp)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun SubscriptionListItem(zoneName: String, confidenceLabel: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(zoneName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(confidenceLabel, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        TextButton(onClick = onRemove) {
            Text("REMOVE", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun subscriptionChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color.Yellow,
    selectedLabelColor = Color.Black,
    containerColor = Color.White.copy(alpha = 0.1f),
    labelColor = Color.White
)

@Composable
private fun SubscriptionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = title,
            color = Color.Yellow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 1.dp)
}
