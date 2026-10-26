package de.letheapp.iris

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

private val registerHttpClient = OkHttpClient()
private val connectedColor = Color(0xFF2E7D32)
private val disconnectedColor = Color(0xFFC62828)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { IrisScreen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun IrisScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var registrations by remember { mutableStateOf(IrisConfig.getRegistrations(context)) }
        var appStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        var daemonStatus by remember { mutableStateOf(DaemonStatus(available = false)) }
        var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
        var showMenu by remember { mutableStateOf(false) }
        var showRegisterDialog by remember { mutableStateOf(false) }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* if denied, the foreground service still runs, just without a visible notification */ }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            while (true) {
                registrations = IrisConfig.getRegistrations(context)
                daemonStatus = IrisStatus.queryDaemon()
                appStatus = withContext(Dispatchers.IO) { IrisStatus.appConnectionStatus(context) }
                batteryExempt = isBatteryExempt(context)
                delay(3000)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Iris Push") },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menü")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("App registrieren") },
                                onClick = {
                                    showMenu = false
                                    showRegisterDialog = true
                                }
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Erkannte Apps", style = MaterialTheme.typography.titleMedium)
                if (registrations.isEmpty()) {
                    Text(
                        "Noch keine App registriert – über das Menü oben rechts (⋮) eine App registrieren.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    registrations.forEach { reg ->
                        AppStatusRow(
                            packageName = reg.packageName,
                            connected = appStatus[reg.packageName] == true,
                            onRemove = {
                                IrisConfig.removeRegistration(context, reg.packageName)
                                registrations = IrisConfig.getRegistrations(context)
                                scope.launch { withContext(Dispatchers.IO) { IrisDelivery.start(context) } }
                            }
                        )
                    }
                }

                HorizontalDivider()

                Text("Akku-Optimierung", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusIcon(batteryExempt)
                    Text(
                        if (batteryExempt) "Von Akku-Optimierung ausgenommen" else "Nicht von Akku-Optimierung ausgenommen",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(
                    onClick = { requestBatteryOptimizationExemption() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Von Akku-Optimierung ausnehmen") }

                HorizontalDivider()

                Text("Systemdienst (irisd)", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusIcon(daemonStatus.available)
                    Text(
                        if (daemonStatus.available)
                            "Nativer irisd-Systemdienst erkannt" +
                                (daemonStatus.version?.let { " (v$it)" } ?: "") +
                                " – Zustellung läuft über diesen (kein eigener Dauer-Socket in dieser App)."
                        else
                            "Kein irisd-Systemdienst erkannt – diese App hält die Verbindung(en) selbst (Normalmodus, z.B. Sideload ohne OS-Integration).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch { withContext(Dispatchers.IO) { IrisDelivery.start(context) } }
                        },
                        enabled = registrations.isNotEmpty()
                    ) { Text("Dienst starten") }

                    OutlinedButton(onClick = { IrisDelivery.stop(context) }) { Text("Dienst stoppen") }
                }
            }
        }

        if (showRegisterDialog) {
            RegisterAppDialog(
                onDismiss = { showRegisterDialog = false },
                onRegistered = { packageName, token ->
                    IrisConfig.upsertRegistration(context, packageName, token)
                    registrations = IrisConfig.getRegistrations(context)
                    scope.launch { withContext(Dispatchers.IO) { IrisDelivery.start(context) } }
                    showRegisterDialog = false
                }
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

private fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
}

@Composable
private fun StatusIcon(ok: Boolean) {
    Icon(
        imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
        contentDescription = if (ok) "OK" else "Nicht verbunden",
        tint = if (ok) connectedColor else disconnectedColor
    )
}

@Composable
private fun AppStatusRow(packageName: String, connected: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusIcon(connected)
        Text(packageName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Entfernen")
        }
    }
}

@Composable
private fun RegisterAppDialog(onDismiss: () -> Unit, onRegistered: (packageName: String, token: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(IrisConfig.getServerUrl(context)) }
    var packageName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var registering by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App registrieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Offener, FCM-unabhängiger Push-Relay-Dienst. Registriert dieses Gerät beim " +
                        "angegebenen Server für die unten genannte App und stellt eingehende Pushes " +
                        "per Broadcast zu (sie benötigt die Permission de.letheapp.iris.RECEIVE_PUSH, " +
                        "mit demselben Signaturschlüssel wie dieser Build).",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server-URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Ziel-App Package-Name") },
                    placeholder = { Text("de.letheapp.lethe") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = serverUrl.isNotBlank() && packageName.isNotBlank() && !registering,
                onClick = {
                    registering = true
                    status = "Registriere…"
                    scope.launch {
                        val token = withContext(Dispatchers.IO) {
                            registerDevice(context, serverUrl, packageName)
                        }
                        registering = false
                        if (token != null) {
                            IrisConfig.setServerUrl(context, serverUrl)
                            onRegistered(packageName, token)
                        } else {
                            status = "Registrierung fehlgeschlagen"
                        }
                    }
                }
            ) { Text("Registrieren / Token erzeugen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

/** POST {serverUrl}/push/register, returns the token on success. */
private fun registerDevice(context: Context, serverUrl: String, targetPackageName: String): String? {
    return try {
        val deviceId = IrisConfig.getDeviceId(context) ?: UUID.randomUUID().toString().also {
            IrisConfig.setDeviceId(context, it)
        }
        val body = JSONObject().apply {
            put("package_name", targetPackageName)
            put("device_id", deviceId)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/push/register")
            .post(body)
            .build()

        registerHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string() ?: return null)
            json.getString("token")
        }
    } catch (_: Exception) {
        null
    }
}
