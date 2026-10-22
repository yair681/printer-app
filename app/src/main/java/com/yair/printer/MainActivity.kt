package com.yair.printer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                // The whole app is Hebrew, so the layout is mirrored once here
                // rather than per screen.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App()
                }
            }
        }
    }
}

private val TABS = listOf("מצב", "סריקה", "הדפסה")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(model: PrinterViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("מדפסת הבית") },
                actions = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).height(22.dp))
                    } else {
                        TextButton(onClick = model::refresh) { Text("רענון") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    0 -> StatusTab(state, model)
                    1 -> ScanTab(state, model)
                    else -> PrintTab()
                }
            }
        }
    }
}

@Composable
private fun StatusTab(state: PrinterState, model: PrinterViewModel) {
    val status = state.status
    val scanner = state.scanner

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                status?.model ?: "מדפסת לא מזוהה",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (status?.reachable == true) {
                Line("מצב", status.state ?: "לא ידוע")
                status.queued?.let { Line("עבודות בתור", it.toString()) }

                status.tonerPercent?.let { level ->
                    Spacer(Modifier.height(4.dp))
                    Text("טונר ${status.tonerName ?: ""} — $level%")
                    LinearProgressIndicator(
                        progress = { level / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (status.colorSupported == false) {
                    Text(
                        "ההדפסה שחור-לבן בלבד",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text("אין קשר: ${status?.error ?: "לא נבדק"}")
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("סורק", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (scanner?.error == null) {
                Line("מצב", if (scanner?.state == "Idle") "פנוי" else scanner?.state ?: "לא ידוע")
                Line("מזין הדפים", if (scanner?.feederLoaded == true) "יש דפים" else "ריק")
            } else {
                Text("אין קשר: ${scanner.error}")
            }

            state.capabilities?.takeIf { it.error == null }?.let { caps ->
                caps.serial?.let { Line("מספר סידורי", it) }
                caps.firmware?.let { Line("קושחה", it) }
                Line("רזולוציות", caps.resolutions.joinToString(", ") + " dpi")
            }
        }
    }

    HostCard(state, model)
}

@Composable
private fun HostCard(state: PrinterState, model: PrinterViewModel) {
    var typed by remember(state.host) { mutableStateOf(state.host) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("כתובת", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            state.discoveredName?.let {
                Text("נמצאה ברשת בשם $it", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("כתובת המדפסת") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.setHost(typed) }) { Text("שמור") }

                OutlinedButton(onClick = model::search, enabled = !state.isSearching) {
                    Text(if (state.isSearching) "מחפש..." else "חפש ברשת")
                }
            }

            Text(
                "החיפוש מוצא את המדפסת לפי השם שהיא משדרת, גם אם הכתובת שלה השתנתה.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ScanTab(state: PrinterState, model: PrinterViewModel) {
    val context = LocalContext.current
    val caps = state.capabilities
    val settings = state.settings

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("מאיפה", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Escl.Source.entries.forEach { source ->
                    val enabled = source == Escl.Source.PLATEN || caps?.hasFeeder == true
                    FilterChip(
                        selected = settings.source == source,
                        onClick = { model.update(settings.copy(source = source)) },
                        label = { Text(source.label) },
                        enabled = enabled,
                    )
                }
            }

            if (settings.source == Escl.Source.FEEDER && state.scanner?.feederLoaded == false) {
                Text("המזין ריק כרגע", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text("צבע", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Escl.Mode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.mode == mode,
                        onClick = { model.update(settings.copy(mode = mode)) },
                        label = { Text(mode.label) },
                    )
                }
            }

            HorizontalDivider()

            Text("רזולוציה", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (caps?.resolutions ?: listOf(100, 200, 300)).forEach { dpi ->
                    FilterChip(
                        selected = settings.dpi == dpi,
                        onClick = { model.update(settings.copy(dpi = dpi)) },
                        label = { Text("$dpi") },
                    )
                }
            }

            HorizontalDivider()

            Text("פורמט", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Escl.Format.entries.forEach { format ->
                    FilterChip(
                        selected = settings.format == format,
                        onClick = { model.update(settings.copy(format = format)) },
                        label = { Text(format.label) },
                    )
                }
            }

            Button(
                onClick = model::scan,
                enabled = !state.isScanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isScanning) "סורק..." else "סרוק")
            }
        }
    }

    if (state.scans.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("סריקות", fontWeight = FontWeight.Bold)

                state.scans.forEach { saved ->
                    val mime = if (saved.name.endsWith(".pdf")) "application/pdf" else "image/jpeg"

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(saved.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "נשמר בתיקיית ${Saver.downloadsLabel} — ${saved.bytes / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                Saver.openIntent(saved, mime)?.let { context.startActivity(it) }
                            }) { Text("פתח") }

                            TextButton(onClick = {
                                Saver.shareIntent(saved, mime)?.let {
                                    context.startActivity(Intent.createChooser(it, "שיתוף"))
                                }
                            }) { Text("שתף") }

                            if (mime == "application/pdf") {
                                TextButton(onClick = {
                                    saved.shareable?.let {
                                        Printing.printPdf(context, it, saved.name)
                                    }
                                }) { Text("הדפס") }
                            }
                        }
                    }

                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PrintTab() {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> picked = uri }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("הדפסת קובץ", fontWeight = FontWeight.Bold)

            Text(
                "בחר PDF או תמונה. חלון ההדפסה של אנדרואיד ייפתח ושם בוחרים את המדפסת.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = { picker.launch(arrayOf("application/pdf", "image/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("בחר קובץ") }

            picked?.let { uri ->
                Text("נבחר: ${uri.lastPathSegment ?: uri.toString()}", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Printing.printPdf(context, uri, "הדפסה") }) {
                        Text("הדפס כ-PDF")
                    }
                    OutlinedButton(onClick = { Printing.printImage(context, uri, "הדפסה") }) {
                        Text("הדפס כתמונה")
                    }
                }
            }

            HorizontalDivider()

            Text(
                "המדפסת הזו מדפיסה שחור-לבן וחד-צדדי בלבד, כך שהיא מדווחת בעצמה.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A label and a value on one line, the label leading so RTL reads correctly. */
@Composable
private fun Line(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
