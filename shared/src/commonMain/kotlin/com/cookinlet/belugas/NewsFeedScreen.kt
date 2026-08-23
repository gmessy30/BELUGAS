package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun NewsFeedScreen(onBack: () -> Unit) {
    var selectedType by remember { mutableStateOf(ArticleContentType.NEWS) }
    var articles by remember { mutableStateOf<List<ArticleRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showSubmitForm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    suspend fun loadArticles(type: ArticleContentType) {
        isLoading = true
        articles = SupabaseApi.getArticles(type)
        isLoading = false
    }

    LaunchedEffect(selectedType) {
        loadArticles(selectedType)
    }

    val glacialBlueGreen = Brush.verticalGradient(colors = listOf(Color(0xFF007F7F), Color(0xFF004D4D)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(glacialBlueGreen)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("NEWS FEED", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onBack) {
                    Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedType == ArticleContentType.NEWS,
                    onClick = { selectedType = ArticleContentType.NEWS },
                    label = { Text("NEWS") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.Yellow,
                        selectedLabelColor = Color.Black,
                        containerColor = Color.White.copy(alpha = 0.1f),
                        labelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedType == ArticleContentType.RESEARCH_PAPER,
                    onClick = { selectedType = ArticleContentType.RESEARCH_PAPER },
                    label = { Text("RESEARCH PAPERS") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E5FF),
                        selectedLabelColor = Color.Black,
                        containerColor = Color.White.copy(alpha = 0.1f),
                        labelColor = Color.White
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.Yellow)
                        }
                    }
                    articles.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (selectedType == ArticleContentType.NEWS) "No news articles yet." else "No research papers yet.",
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(articles) { article ->
                                ArticleCard(article = article, onClick = { uriHandler.openUri(article.sourceUrl) })
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { showSubmitForm = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Text("+ SUGGEST AN ARTICLE OR PAPER", color = Color.Black, fontWeight = FontWeight.Black)
            }
        }
    }

    if (showSubmitForm) {
        SubmitArticleDialog(
            defaultContentType = selectedType,
            onDismiss = { showSubmitForm = false },
            onSubmitted = {
                showSubmitForm = false
                // Submitted items are pending_review and won't show up here until approved,
                // so there's nothing to refresh -- just close the form.
            }
        )
    }
}

@Composable
private fun ArticleCard(article: ArticleRecord, onClick: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.25f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(article.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (!article.summary.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(article.summary, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Open link →", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SubmitArticleDialog(
    defaultContentType: ArticleContentType,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var submittedBy by remember { mutableStateOf("") }
    var contentType by remember { mutableStateOf(defaultContentType) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = onSubmitted,
            title = { Text("Thanks!") },
            text = { Text("Submitted for review. It'll appear here once approved.") },
            confirmButton = {
                TextButton(onClick = onSubmitted) { Text("OK") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suggest an Article or Paper", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = contentType == ArticleContentType.NEWS,
                        onClick = { contentType = ArticleContentType.NEWS },
                        label = { Text("News", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = contentType == ArticleContentType.RESEARCH_PAPER,
                        onClick = { contentType = ArticleContentType.RESEARCH_PAPER },
                        label = { Text("Research Paper", fontSize = 11.sp) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Short note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = submittedBy,
                    onValueChange = { submittedBy = it },
                    label = { Text("Your name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && sourceUrl.isNotBlank() && !isSubmitting,
                onClick = {
                    isSubmitting = true
                    scope.launch {
                        SupabaseApi.submitArticle(
                            title = title.trim(),
                            sourceUrl = sourceUrl.trim(),
                            summary = note.trim().ifBlank { null },
                            submittedBy = submittedBy.trim().ifBlank { null },
                            contentType = contentType
                        )
                        isSubmitting = false
                        showConfirmation = true
                    }
                }
            ) {
                Text(if (isSubmitting) "SUBMITTING..." else "SUBMIT")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}
