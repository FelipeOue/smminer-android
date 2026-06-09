package com.aonminers.smminer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aonminers.smminer.ui.theme.TerminalBackground
import com.aonminers.smminer.ui.theme.TerminalGreen
import com.aonminers.smminer.ui.theme.SmminerTheme
import android.content.SharedPreferences
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Persistent settings backed by SharedPreferences. */
object SettingsStore {
    private const val NAME = "smminer_prefs"
    private const val KEY_POOL_URL = "poolUrl"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SUGGEST_DIFF = "suggestDiff"
    private const val KEY_FREQUENCY = "frequency"
    private const val KEY_DEBUG = "debug"
    private const val KEY_LANGUAGE = "language"

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE) }
    private fun p(): SharedPreferences = prefs!!

    fun loadPoolUrl(default: String) = p().getString(KEY_POOL_URL, default) ?: default
    fun loadUsername(default: String) = p().getString(KEY_USERNAME, default) ?: default
    fun loadPassword(default: String) = p().getString(KEY_PASSWORD, default) ?: default
    fun loadSuggestDiff(default: String) = p().getString(KEY_SUGGEST_DIFF, default) ?: default
    fun loadFrequency(default: String) = p().getString(KEY_FREQUENCY, default) ?: default
    fun loadDebug(default: Boolean) = p().getBoolean(KEY_DEBUG, default)
    fun loadLanguage(default: Language) = Language.valueOf(p().getString(KEY_LANGUAGE, default.name) ?: default.name)

    fun savePoolUrl(v: String) = p().edit().putString(KEY_POOL_URL, v).apply()
    fun saveUsername(v: String) = p().edit().putString(KEY_USERNAME, v).apply()
    fun savePassword(v: String) = p().edit().putString(KEY_PASSWORD, v).apply()
    fun saveSuggestDiff(v: String) = p().edit().putString(KEY_SUGGEST_DIFF, v).apply()
    fun saveFrequency(v: String) = p().edit().putString(KEY_FREQUENCY, v).apply()
    fun saveDebug(v: Boolean) = p().edit().putBoolean(KEY_DEBUG, v).apply()
    fun saveLanguage(v: Language) = p().edit().putString(KEY_LANGUAGE, v.name).apply()
}

/** Shared state for USB permission flow. */
class UsbPermissionState {
    val lock = Object()
    @Volatile var granted = false
}

/** Survives Activity recreation so mining threads aren't orphaned on rotation. */
object MiningSession {
    var stratumClient: StratumClient? = null
    var isMining: Boolean = false
    val logLines: SnapshotStateList<String> = mutableStateListOf("> smminer v1.0")
}

class MainActivity : ComponentActivity() {

    val usbPermissionState = UsbPermissionState()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                synchronized(usbPermissionState.lock) {
                    usbPermissionState.granted = ok
                    usbPermissionState.lock.notifyAll()
                }
                android.util.Log.d(TAG, "USB permission ${if (ok) "granted" else "denied"} for ${device?.deviceName}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register USB permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            SmminerTheme {
                SmminerApp(
                usbPermissionState = usbPermissionState,
                requestUsbPermission = { devices -> requestUsbPermission(devices) }
            )
            }
        }
    }

    /** Request USB permission for FTDI devices. Call before UsbPort.scan. */
    private fun requestUsbPermission(devices: List<UsbDevice>) {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        for (device in devices) {
            if (!usbManager.hasPermission(device)) {
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                usbManager.requestPermission(device, pi)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "SMMiner"
        private const val ACTION_USB_PERMISSION = "com.aonminers.smminer.USB_PERMISSION"
        private const val NOTIF_PERM_REQ = 1001
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERM_REQ) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            android.util.Log.d(TAG, "Notification permission ${if (granted) "granted" else "denied"}")
        }
    }
}

enum class Page { LOG, CONFIG }

enum class Language { EN, PT }

object S {
    fun log(lang: Language) = when (lang) { Language.EN -> "Log"; Language.PT -> "Registro" }
    fun start(lang: Language) = when (lang) { Language.EN -> "Start"; Language.PT -> "Iniciar" }
    fun stop(lang: Language) = when (lang) { Language.EN -> "Stop"; Language.PT -> "Parar" }
    fun config(lang: Language) = when (lang) { Language.EN -> "Config."; Language.PT -> "Config." }
    fun configuration(lang: Language) = when (lang) { Language.EN -> "Configuration"; Language.PT -> "Configuração" }
    fun poolUrl(lang: Language) = when (lang) { Language.EN -> "Pool URL"; Language.PT -> "URL da Pool" }
    fun username(lang: Language) = when (lang) { Language.EN -> "Username/Wallet"; Language.PT -> "Usuário/Carteira" }
    fun password(lang: Language) = when (lang) { Language.EN -> "Password"; Language.PT -> "Senha" }
    fun suggestDifficulty(lang: Language) = when (lang) { Language.EN -> "Suggest Difficulty"; Language.PT -> "Sugerir Dificuldade" }
    fun frequency(lang: Language) = when (lang) { Language.EN -> "Frequency"; Language.PT -> "Frequência" }
    fun debug(lang: Language) = when (lang) { Language.EN -> "Debug Mode"; Language.PT -> "Modo depuração" }
    fun language(lang: Language) = when (lang) { Language.EN -> "Language"; Language.PT -> "Idioma" }
    fun debugWarningTitle(lang: Language) = when (lang) { Language.EN -> "Debug Mode"; Language.PT -> "Modo depuração" }
    fun debugWarningMessage(lang: Language) = when (lang) {
        Language.EN -> "Debug Mode can drastically reduce hashrate. This is only suitable for testing, not normal mining operation. Are you sure you want to enable it?"
        Language.PT -> "Modo depuração pode reduzir drasticamente o hashrate. É adequado apenas para testes, não para mineração normal. Tem certeza que deseja ativá-lo?"
    }
    fun yes(lang: Language) = when (lang) { Language.EN -> "Yes"; Language.PT -> "Sim" }
    fun no(lang: Language) = when (lang) { Language.EN -> "No"; Language.PT -> "Não" }
}

@Composable
fun SmminerApp(
    usbPermissionState: UsbPermissionState = UsbPermissionState(),
    requestUsbPermission: (List<android.hardware.usb.UsbDevice>) -> Unit = {}
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // ── Init persistent storage ──
    remember { SettingsStore.init(appContext.applicationContext) }

    var currentPage by remember { mutableStateOf(Page.LOG) }
    var isMining by remember { mutableStateOf(MiningSession.isMining) }
    var language by remember { mutableStateOf(SettingsStore.loadLanguage(Language.PT)) }
    val logLines = MiningSession.logLines
    val stratumClient = remember { mutableStateOf(MiningSession.stratumClient) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val safeAddLog: (String) -> Unit = remember { { msg ->
        mainHandler.post {
            logLines.add(msg)
            if (logLines.size > 200) {
                val excess = logLines.size - 200
                repeat(excess) { logLines.removeAt(0) }
            }
        }
    } }

    // Config state — loaded from persistent storage
    var poolUrl by remember { mutableStateOf(SettingsStore.loadPoolUrl("solo.ckpool.org:3333")) }
    var username by remember { mutableStateOf(SettingsStore.loadUsername("")) }
    var password by remember { mutableStateOf(SettingsStore.loadPassword("x")) }
    var suggestDifficulty by remember { mutableStateOf(SettingsStore.loadSuggestDiff("254")) }
    var frequency by remember { mutableStateOf(SettingsStore.loadFrequency("150")) }
    var debug by remember { mutableStateOf(SettingsStore.loadDebug(false)) }
    var showDebugWarning by remember { mutableStateOf(false) }

    // Debug mode confirmation dialog
    if (showDebugWarning) {
        AlertDialog(
            onDismissRequest = { showDebugWarning = false },
            title = { Text(S.debugWarningTitle(language)) },
            text = { Text(S.debugWarningMessage(language)) },
            confirmButton = {
                TextButton(onClick = {
                    showDebugWarning = false
                    debug = true
                    SettingsStore.saveDebug(true)
                }) {
                    Text(S.yes(language))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugWarning = false }) {
                    Text(S.no(language))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomBar(
                currentPage = currentPage,
                isMining = isMining,
                language = language,
                onLogClick = { currentPage = Page.LOG },
                onStartClick = {
                    if (!isMining) {
                        // Request notification permission on Android 13+
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                            (appContext as? android.app.Activity)?.let { act ->
                                ActivityCompat.requestPermissions(
                                    act,
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    1001
                                )
                            }
                        }

                        // Apply user-configured frequency before starting
                        AON_FREQUENCY = frequency.toIntOrNull() ?: 150

                        val client = StratumClient(
                            poolUrl = poolUrl,
                            username = username,
                            password = password,
                            suggestDifficulty = suggestDifficulty.toDoubleOrNull() ?: 254.0,
                            debug = debug,
                            lang = language,
                            onLog = { msg -> safeAddLog(msg) }
                        )
                        stratumClient.value = client
                        MiningSession.stratumClient = client
                        client.start()
                        // wait for stratum, request USB permission, then scan
                        thread(name = "aon-launcher") {
                            try {
                                while (!client.isConnected && client.isAlive) Thread.sleep(200)
                                if (!client.isConnected) return@thread

                                val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
                                if (usbManager == null) {
                                    safeAddLog("> USB service unavailable")
                                    return@thread
                                }

                                // Check for FTDI devices
                                val devices = UsbPort.getDevices(appContext)
                                if (devices.isEmpty()) {
                                    safeAddLog("> no FTDI devices connected (VID:0403 PID:6015)")
                                    safeAddLog("> connect USB-OTG cable with AonMiner device")
                                    return@thread
                                }

                                // Check if permission already granted
                                var allHavePermission = devices.all { usbManager.hasPermission(it) }
                                if (!allHavePermission) {
                                    safeAddLog("> requesting USB permission...")
                                    synchronized(usbPermissionState.lock) {
                                        usbPermissionState.granted = false
                                    }
                                    requestUsbPermission(devices)
                                    // Wait for user to grant (up to 15s)
                                    synchronized(usbPermissionState.lock) {
                                        usbPermissionState.lock.wait(15_000)
                                    }
                                }

                                // Now try to open devices (retry a few times)
                                var ports = emptyList<UsbPort>()
                                for (attempt in 1..5) {
                                    ports = UsbPort.scan(appContext, "ZX1") { msg -> safeAddLog(msg) }
                                    if (ports.isNotEmpty()) break
                                    safeAddLog("> scan attempt $attempt/5...")
                                    // Close any stale connections first
                                    try {
                                        val allDevs = UsbPort.getDevices(appContext)
                                        for (d in allDevs) {
                                            try { usbManager.openDevice(d)?.close() } catch (_: Exception) {}
                                        }
                                    } catch (_: Exception) {}
                                    Thread.sleep(500)
                                }

                                if (ports.isEmpty()) {
                                    safeAddLog("> could not open device after 5 attempts")
                                    safeAddLog("> unplug and replug the USB device, then try again")
                                } else {
                                    safeAddLog("> found ${ports.size} device(s), initializing...")
                                    startAonMiner(ports, client, context = appContext.applicationContext, debug = debug, language = language, log = { msg -> safeAddLog(msg) }, poolUrl = poolUrl, username = username, password = password, suggestDifficulty = suggestDifficulty.toDoubleOrNull() ?: 254.0)
                                }
                            } catch (e: Exception) {
                                safeAddLog("> miner launch error: ${e.message}")
                            }
                        }
                    } else {
                        stopAonMiner()
                        stratumClient.value?.shutdown()
                        stratumClient.value?.join(2000)
                        stratumClient.value = null
                        MiningSession.stratumClient = null
                    }
                    isMining = !isMining
                    MiningSession.isMining = isMining
                },
                onConfigClick = { currentPage = Page.CONFIG }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentPage) {
                Page.LOG -> LogView(logLines)
                Page.CONFIG -> ConfigView(
                    language = language,
                    onLanguageChange = { language = it; SettingsStore.saveLanguage(it) },
                    poolUrl = poolUrl,
                    onPoolUrlChange = { poolUrl = it; SettingsStore.savePoolUrl(it) },
                    username = username,
                    onUsernameChange = { username = it; SettingsStore.saveUsername(it) },
                    password = password,
                    onPasswordChange = { password = it; SettingsStore.savePassword(it) },
                    suggestDifficulty = suggestDifficulty,
                    onSuggestDifficultyChange = { suggestDifficulty = it; SettingsStore.saveSuggestDiff(it) },
                    frequency = frequency,
                    onFrequencyChange = { frequency = it; SettingsStore.saveFrequency(it) },
                    debug = debug,
                    onDebugChange = { enabled ->
                        if (enabled) showDebugWarning = true
                        else { debug = false; SettingsStore.saveDebug(false) }
                    }
                )
            }
        }
    }
}

@Composable
fun BottomBar(
    currentPage: Page,
    isMining: Boolean,
    language: Language,
    onLogClick: () -> Unit,
    onStartClick: () -> Unit,
    onConfigClick: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Log button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(onClick = onLogClick)
                    .padding(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_log),
                    contentDescription = S.log(language),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    S.log(language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Start / Stop button
            val buttonColor = if (isMining) Color(0xFFE53935) else Color(0xFF43A047)
            val buttonIcon = if (isMining) R.drawable.ic_stop else R.drawable.ic_play
            val buttonLabel = if (isMining) S.stop(language) else S.start(language)

            Button(
                onClick = onStartClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(28.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    painter = painterResource(buttonIcon),
                    contentDescription = buttonLabel,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(buttonLabel)
            }

            // Config button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(onClick = onConfigClick)
                    .padding(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_config),
                    contentDescription = S.config(language),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    S.config(language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun LogView(logLines: List<String>) {
    val listState = rememberLazyListState()

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(10.dp)
    ) {
        items(logLines) { line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1990EE90))
                    .padding(vertical = 0.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = line,
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun ConfigView(
    language: Language,
    onLanguageChange: (Language) -> Unit,
    poolUrl: String,
    onPoolUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    suggestDifficulty: String,
    onSuggestDifficultyChange: (String) -> Unit,
    frequency: String,
    onFrequencyChange: (String) -> Unit,
    debug: Boolean,
    onDebugChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            S.configuration(language),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Language toggle
        Text(
            S.language(language),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Language.entries.forEach { lang ->
                val isSelected = lang == language
                val label = when (lang) { Language.PT -> "Português"; Language.EN -> "English" }
                Surface(
                    onClick = { onLanguageChange(lang) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF1A3A6E)
                            else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .then(
                            if (!isSelected) Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            ) else Modifier
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = poolUrl,
            onValueChange = onPoolUrlChange,
            label = { Text(S.poolUrl(language)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(S.username(language)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(S.password(language)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        OutlinedTextField(
            value = suggestDifficulty,
            onValueChange = onSuggestDifficultyChange,
            label = { Text(S.suggestDifficulty(language)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        OutlinedTextField(
            value = frequency,
            onValueChange = onFrequencyChange,
            label = { Text(S.frequency(language)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                S.debug(language),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                checked = debug,
                onCheckedChange = onDebugChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A3A6E),
                    checkedTrackColor = Color(0xFF3A5A9E)
                )
            )
        }
    }
}
