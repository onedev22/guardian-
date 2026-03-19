package com.amurayada.guardianapp.ui.emergency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurayada.guardianapp.data.local.AppDatabase
import com.amurayada.guardianapp.data.model.MedicalInfo
import android.os.CountDownTimer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import kotlinx.coroutines.delay

@Composable
fun EmergencyAlertScreen(
    maxAcceleration: Float,
    isTestMode: Boolean = false,
    onCancel: () -> Unit,
    onTimeout: () -> Unit,
    onArrivedAtHospital: () -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val medicalInfo by database.medicalInfoDao().getMedicalInfo().collectAsState(initial = null)
    
    var secondsRemaining by remember { mutableStateOf(10) }
    var alertSent by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
        }
        alertSent = true
        onTimeout()
    }
    
    // Use error colors for emergency state
    val backgroundColor = MaterialTheme.colorScheme.error
    val contentColor = MaterialTheme.colorScheme.onError
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Test Mode Banner
                if (isTestMode) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "🧪 MODO PRUEBA",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Header Status
                Text(
                    text = if (alertSent) {
                        if (isTestMode) "✅ PRUEBA COMPLETADA" else "📍 COMPARTIENDO UBICACIÓN"
                    } else {
                        "⚠️ ACCIDENTE DETECTADO"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
                
                if (!alertSent) {
                    // Countdown
                    Surface(
                        modifier = Modifier.size(120.dp),
                        shape = RoundedCornerShape(60.dp),
                        color = contentColor.copy(alpha = 0.3f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = secondsRemaining.toString(),
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                        }
                    }
                    
                    Text(
                        text = "Alertando contactos de emergencia...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor.copy(alpha = 0.9f)
                    )
                } else {
                    // Post-alert message
                    Text(
                        text = if (isTestMode) 
                            "La simulación ha finalizado. En un caso real, se habrían enviado SMS y realizado llamadas." 
                        else 
                            "Se está enviando tu ubicación cada 15 minutos a tus contactos de emergencia.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                
                // Medical Info Card - ALWAYS VISIBLE
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "INFORMACIÓN MÉDICA",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        
                        if (medicalInfo != null) {
                            val info = medicalInfo!!
                            if (info.fullName.isNotEmpty()) InfoRow("Nombre", info.fullName)
                            if (info.bloodType.isNotEmpty()) InfoRow("Sangre", info.bloodType)
                            if (info.allergies.isNotEmpty()) InfoRow("Alergias", info.allergies)
                            if (info.medications.isNotEmpty()) InfoRow("Medicamentos", info.medications)
                            if (info.medicalConditions.isNotEmpty()) InfoRow("Condiciones", info.medicalConditions)
                            if (info.address.isNotEmpty()) InfoRow("Dirección", info.address)
                            if (info.emergencyNotes.isNotEmpty()) InfoRow("Notas", info.emergencyNotes)
                        } else {
                            Text(
                                text = "No hay información médica registrada.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Action Buttons
                if (!alertSent) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "ESTOY BIEN - CANCELAR",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Button for Hospital Arrival
                    Button(
                        onClick = onArrivedAtHospital,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "YA ESTOY EN EL HOSPITAL",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f)
        )
    }
}
