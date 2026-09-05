package com.cookinlet.belugas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Static content -- no backend needed. Facts below (hotline number, viewing-distance
// guidance, org URLs) were verified against real NOAA/ADF&G/Alaska Wildlife Alliance sources
// rather than guessed, since this is exactly the kind of content that's actively harmful if
// wrong (a wrong stranding-hotline number, a dead link).
private const val NOAA_STRANDING_HOTLINE_DISPLAY = "(877) 925-7773"
private const val NOAA_STRANDING_HOTLINE_TEL = "tel:8779257773"
private const val NOAA_STRANDING_HOTLINE_INFO_URL =
    "https://www.fisheries.noaa.gov/contact/alaska-marine-mammal-stranding-network-24-hour-hotline"
private const val NOAA_VIEWING_GUIDELINES_URL =
    "https://www.fisheries.noaa.gov/alaska/marine-life-viewing-guidelines/alaska-marine-mammal-viewing-guidelines-and-regulations"
private const val ADFG_BELUGA_URL =
    "https://www.adfg.alaska.gov/index.cfm?adfg=wildlifediversity.esalisted&id=cook-inlet-beluga-whale"
private const val ADFG_BELUGA_RESEARCH_URL =
    "https://www.adfg.alaska.gov/index.cfm?adfg=wildliferesearch.beluga"
private const val AWA_BELUGA_CAMS_URL = "https://www.akwildlife.org/news/belugacams"

@Composable
fun ResourcesScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    AppBackground {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RESOURCES", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onBack) {
                    Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ResourceSection(title = "REPORT A STRANDING") {
                    Text(
                        "See an injured, entangled, or dead marine mammal? Call NOAA's 24-hour Alaska Marine Mammal Stranding Network hotline.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFF9800),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(NOAA_STRANDING_HOTLINE_TEL) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📞 $NOAA_STRANDING_HOTLINE_DISPLAY", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("TAP TO CALL", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    ResourceLinkRow("More about the stranding network →", NOAA_STRANDING_HOTLINE_INFO_URL, uriHandler)
                }

                ResourceSection(title = "KEEP YOUR DISTANCE") {
                    Text(
                        "NOAA's Alaska marine mammal viewing guidelines recommend staying at least " +
                            "100 yards from marine mammals and limiting time observing any one animal " +
                            "or group to 30 minutes. Never encircle or trap a whale between boats, or " +
                            "between a boat and shore. If a whale approaches you, put your engine in " +
                            "neutral and let it pass on its own.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    ResourceLinkRow("Full NOAA viewing guidelines →", NOAA_VIEWING_GUIDELINES_URL, uriHandler)
                }

                ResourceSection(title = "ALASKA STATE WILDLIFE RESOURCES") {
                    ResourceLinkRow("ADF&G: Cook Inlet Beluga Whale", ADFG_BELUGA_URL, uriHandler)
                    ResourceLinkRow("ADF&G: Beluga Whale Management & Research", ADFG_BELUGA_RESEARCH_URL, uriHandler)
                }

                ResourceSection(title = "HOW TO USE THIS APP") {
                    // Longer, explanatory version of the same behavioral framing shown once as
                    // a first-run gate (AcknowledgementGateScreen.kt) -- that one is deliberately
                    // short since it's a one-time interstitial with a checkbox; this one has
                    // room to actually explain why, for anyone who wants the reasoning later.
                    Text(
                        "Many good viewing spots along the Kenai and Kasilof are on or accessed " +
                            "through private property. Always get permission before crossing a " +
                            "fence line or cutting through someone's yard to reach the water -- " +
                            "public road pullouts and posted public access points exist for a " +
                            "reason.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "When you log a sighting, this app asks for the whale's position, not " +
                            "yours -- that's deliberate. A report is far more useful to other " +
                            "observers and researchers when it marks where the animal actually " +
                            "was, so take the extra moment to estimate that rather than just " +
                            "dropping a pin on your own location.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The predicted arrival windows on the Kenai map are estimates from a " +
                            "model that's still being built, not a finished forecast. They're " +
                            "most reliable when the low tide is below 0.0m and the current has " +
                            "had time to build behind it -- above that, the current isn't well " +
                            "understood yet, so treat those windows as a rougher guess. We're " +
                            "testing this in the open this season instead of holding it back " +
                            "until it's finished, so expect it to keep improving as we go.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                }

                ResourceSection(title = "BELUGA PHOTOGRAPHY TIPS") {
                    val tips = listOf(
                        "Use a zoom or telephoto lens rather than approaching closer -- belugas surface briefly and a long lens keeps you well within safe viewing distance.",
                        "Shoot with the sun behind you when possible; backlit water hides a pale beluga against glare.",
                        "Belugas are easiest to spot on a rising or falling tide near river mouths -- patience matters more than gear.",
                        "Burst/continuous shooting mode helps catch a surfacing that only lasts a second or two.",
                        "Skip the flash -- it does nothing at typical viewing distances and isn't worth disturbing wildlife or other observers for."
                    )
                    tips.forEach { tip ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("•  ", color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(tip, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                        }
                    }
                }

                ResourceSection(title = "LIVE BELUGA CAMS") {
                    Text(
                        "Alaska Wildlife Alliance runs live viewing cameras at the mouth of the Kenai River.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    ResourceLinkRow("Watch the Alaska Wildlife Alliance beluga cams →", AWA_BELUGA_CAMS_URL, uriHandler)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun ResourceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
private fun ResourceLinkRow(label: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    Text(
        text = label,
        color = Color(0xFF00E5FF),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable { uriHandler.openUri(url) }
    )
}
