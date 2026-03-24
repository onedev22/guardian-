package com.amurayada.guardianapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.amurayada.guardianapp.data.local.AppDatabase
import com.amurayada.guardianapp.data.model.EmergencyContact
import com.amurayada.guardianapp.service.CrashDetectionService
import com.amurayada.guardianapp.ui.screens.*
import com.amurayada.guardianapp.ui.graphs.SensorGraphsScreen
import com.amurayada.guardianapp.ui.theme.GuardianAppTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private lateinit var database: AppDatabase
    private lateinit var prefs: SharedPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        database = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        
        setContent {
            GuardianAppTheme {
                GuardianApp(
                    database = database,
                    prefs = prefs,
                    onStartService = { CrashDetectionService.start(this) },
                    onStopService = { CrashDetectionService.stop(this) }
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GuardianApp(
    database: AppDatabase,
    prefs: SharedPreferences,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    
    // Permissions - Split into basic and background location
    val basicPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }
    
    val basicPermissionsState = rememberMultiplePermissionsState(basicPermissions)
    
    // Background location needs to be requested separately on Android 10+
    val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        emptyList()
    }
    
    val backgroundLocationState = rememberMultiplePermissionsState(backgroundLocationPermission)
    
    // Combined permission state
    val allPermissionsGranted = basicPermissionsState.allPermissionsGranted && 
        (backgroundLocationPermission.isEmpty() || backgroundLocationState.allPermissionsGranted)
    
    // State
    var isMonitoring by remember { 
        mutableStateOf(prefs.getBoolean("is_monitoring", false)) 
    }
    var sensitivity by remember { 
        mutableStateOf(prefs.getFloat("sensitivity", 4.5f)) 
    }
    var emergencyNumber by remember {
        mutableStateOf(prefs.getString("emergency_number", "123") ?: "123")
    }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var showEmergencyNumberDialog by remember { mutableStateOf(false) }
    
    val bleManager = remember { com.amurayada.guardianapp.bluetooth.BLEManager.getInstance(context) }
    val connectionState by bleManager.connectionState.collectAsState()
    val heartRate by bleManager.heartRate.collectAsState()
    
    val crashEvents by database.crashEventDao().getAllEvents().collectAsState(initial = emptyList())
    val emergencyContacts by database.emergencyContactDao().getAllContacts().collectAsState(initial = emptyList())
    val crashEventCount by database.crashEventDao().getEventCount().collectAsState(initial = 0)
    val emergencyContactCount by database.emergencyContactDao().getContactCount().collectAsState(initial = 0)
    
    // Request ALL permissions automatically on app start
    LaunchedEffect(Unit) {
        // Step 1: Request basic runtime permissions first
        if (!basicPermissionsState.allPermissionsGranted) {
            basicPermissionsState.launchMultiplePermissionRequest()
        }
    }
    
    // Step 2: Request background location AFTER basic permissions are granted
    LaunchedEffect(basicPermissionsState.allPermissionsGranted) {
        if (basicPermissionsState.allPermissionsGranted && 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !backgroundLocationState.allPermissionsGranted) {
            // Small delay to let the user see the first permission dialog closed
            kotlinx.coroutines.delay(500)
            backgroundLocationState.launchMultiplePermissionRequest()
        }
    }
    
    
    // Step 3: Request overlay permission after all runtime permissions
    LaunchedEffect(allPermissionsGranted) {
        if (allPermissionsGranted && 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
            !Settings.canDrawOverlays(context)) {
            // Small delay before showing overlay dialog
            kotlinx.coroutines.delay(500)
            showOverlayPermissionDialog = true
        }
    }
    
    // Step 4: Show emergency number setup dialog after all permissions (including overlay)
    LaunchedEffect(allPermissionsGranted, Settings.canDrawOverlays(context)) {
        val isEmergencyNumberConfigured = prefs.getBoolean("emergency_number_configured", false)
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        
        if (allPermissionsGranted && hasOverlayPermission && !isEmergencyNumberConfigured) {
            kotlinx.coroutines.delay(500)
            showEmergencyNumberDialog = true
        }
    }
    
    // Auto-start service if monitoring is enabled and permissions are granted
    LaunchedEffect(isMonitoring, allPermissionsGranted) {
        if (isMonitoring && allPermissionsGranted) {
            onStartService()
        }
    }
    
    // Overlay permission dialog
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("Permiso Requerido") },
            text = { Text("Para mostrar la alerta de emergencia sobre otras aplicaciones y la pantalla de bloqueo, Guardian necesita el permiso 'Mostrar sobre otras apps'.") },
            confirmButton = {
                TextButton(onClick = {
                    showOverlayPermissionDialog = false
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback if package specific intent fails
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(fallbackIntent)
                    }
                }) {
                    Text("Conceder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("Ahora no")
                }
            }
        )
    }
    
    // Emergency Number Setup Dialog
    if (showEmergencyNumberDialog) {
        EmergencyNumberSetupDialog(
            onDismiss = { showEmergencyNumberDialog = false },
            onSave = { number ->
                emergencyNumber = number
                prefs.edit()
                    .putString("emergency_number", number)
                    .putBoolean("emergency_number_configured", true)
                    .apply()
                showEmergencyNumberDialog = false
            }
        )
    }
    
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                isMonitoring = isMonitoring,
                hasPermissions = allPermissionsGranted,
                crashEventCount = crashEventCount,
                emergencyContactCount = emergencyContactCount,
                recentEvents = crashEvents.take(5),
                connectionState = connectionState,
                heartRate = heartRate,
                onToggleMonitoring = { enabled ->
                    isMonitoring = enabled
                    prefs.edit().putBoolean("is_monitoring", enabled).apply()
                    
                    if (enabled) {
                        if (allPermissionsGranted) {
                            onStartService()
                            android.widget.Toast.makeText(context, "✅ Monitoreo activado", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "⚠️ Faltan permisos para iniciar", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        onStopService()
                        android.widget.Toast.makeText(context, "🛑 Monitoreo detenido", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onRequestPermissions = { 
                    // Direct navigation to settings as fallback is most reliable when permissions are denied
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                onNavigateToContacts = { navController.navigate("contacts") },
                onNavigateToHistory = { navController.navigate("history") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToMedicalInfo = { navController.navigate("medical_info") },
                onNavigateToBLE = { navController.navigate("ble_pairing") }
            )
        }
        
        composable("ble_pairing") {
            // Screen to be implemented in Phase 2
            BLEPairingScreen(
                onBack = { navController.popBackStack() }
            )
        }
        
        composable("contacts") {
            // Contact picker launcher
            val contactPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickContact()
            ) { uri ->
                uri?.let {
                    val cursor = context.contentResolver.query(it, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                            val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
                            
                            val contactName = if (nameIndex >= 0) c.getString(nameIndex) else "Unknown"
                            val contactId = if (idIndex >= 0) c.getString(idIndex) else null
                            
                            // Get phone number
                            var phoneNumber = ""
                            contactId?.let { id ->
                                val phoneCursor = context.contentResolver.query(
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                    null,
                                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                    arrayOf(id),
                                    null
                                )
                                phoneCursor?.use { pc ->
                                    if (pc.moveToFirst()) {
                                        val phoneIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                        phoneNumber = if (phoneIndex >= 0) pc.getString(phoneIndex) else ""
                                    }
                                }
                            }
                            
                            // Add contact to database
                            if (phoneNumber.isNotEmpty()) {
                                val contact = EmergencyContact(
                                    name = contactName,
                                    phoneNumber = phoneNumber,
                                    relationship = ""
                                )
                                scope.launch {
                                    database.emergencyContactDao().insertContact(contact)
                                }
                            }
                        }
                    }
                }
            }
            
            EmergencyContactsScreen(
                contacts = emergencyContacts,
                onAddContact = { name, phone, relationship ->
                    val contact = EmergencyContact(
                        name = name,
                        phoneNumber = phone,
                        relationship = relationship
                    )
                    scope.launch {
                        database.emergencyContactDao().insertContact(contact)
                    }
                },
                onDeleteContact = { contact ->
                    scope.launch {
                        database.emergencyContactDao().deleteContact(contact)
                    }
                },
                onPickContact = {
                    contactPickerLauncher.launch(null)
                },
                onBack = { navController.popBackStack() }
            )
        }
        
        composable("history") {
            CrashHistoryScreen(
                events = crashEvents,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                sensitivity = sensitivity,
                onSensitivityChange = { newValue ->
                    sensitivity = newValue
                    prefs.edit().putFloat("sensitivity", newValue).apply()
                },
                emergencyNumber = emergencyNumber,
                onEmergencyNumberChange = { newNumber ->
                    emergencyNumber = newNumber
                    prefs.edit().putString("emergency_number", newNumber).apply()
                },
                onTestCrash = {
                    // Trigger test crash alert
                    val intent = Intent(context, com.amurayada.guardianapp.ui.emergency.EmergencyAlertActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("max_acceleration", 35.0f) // Simulated high acceleration
                        putExtra("test_mode", true) // MODO PRUEBA - No enviará SMS
                    }
                    context.startActivity(intent)
                },
                onNavigateToGraphs = { navController.navigate("sensor_graphs") },
                onBack = { navController.popBackStack() }
            )
        }
        
        composable("sensor_graphs") {
            SensorGraphsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        
        composable("medical_info") {
            val medicalInfo by database.medicalInfoDao().getMedicalInfo().collectAsState(initial = null)
            
            MedicalInfoScreen(
                medicalInfo = medicalInfo,
                onSave = { info ->
                    scope.launch {
                        database.medicalInfoDao().insertOrUpdate(info)
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
@Composable
fun EmergencyNumberSetupDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var selectedOption by remember { mutableStateOf("colombia") }
    var customNumber by remember { mutableStateOf("") }
    
    val countries = mapOf(
        "colombia" to "123",
        "mexico" to "911",
        "spain" to "112",
        "usa" to "911",
        "argentina" to "911",
        "chile" to "131",
        "custom" to customNumber
    )
    
    val countryNames = mapOf(
        "colombia" to "🇨🇴 Colombia (123)",
        "mexico" to "🇲🇽 México (911)",
        "spain" to "🇪🇸 España (112)",
        "usa" to "🇺🇸 USA (911)",
        "argentina" to "🇦🇷 Argentina (911)",
        "chile" to "🇨🇱 Chile (131)",
        "custom" to "✏️ Personalizado"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Configurar Número de Emergencia",
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Selecciona tu país o ingresa un número personalizado para pruebas:",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                countries.keys.forEach { country ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOption == country,
                            onClick = { selectedOption = country }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = countryNames[country] ?: "",
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    if (country == "custom" && selectedOption == "custom") {
                        OutlinedTextField(
                            value = customNumber,
                            onValueChange = { customNumber = it },
                            label = { Text("Número personalizado") },
                            placeholder = { Text("Ej: 3001234567") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 48.dp),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val numberToSave = if (selectedOption == "custom") {
                        customNumber
                    } else {
                        countries[selectedOption] ?: "123"
                    }
                    if (numberToSave.isNotBlank()) {
                        onSave(numberToSave)
                    }
                },
                enabled = if (selectedOption == "custom") customNumber.isNotBlank() else true
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Ahora no")
            }
        }
    )
}
