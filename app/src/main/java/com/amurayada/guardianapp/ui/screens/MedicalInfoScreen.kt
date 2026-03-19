package com.amurayada.guardianapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amurayada.guardianapp.data.model.MedicalInfo

@Composable
fun MedicalInfoScreen(
    medicalInfo: MedicalInfo?,
    onSave: (MedicalInfo) -> Unit,
    onBack: () -> Unit
) {
    var fullName by remember { mutableStateOf(medicalInfo?.fullName ?: "") }
    var bloodType by remember { mutableStateOf(medicalInfo?.bloodType ?: "") }
    var allergies by remember { mutableStateOf(medicalInfo?.allergies ?: "") }
    var medications by remember { mutableStateOf(medicalInfo?.medications ?: "") }
    var medicalConditions by remember { mutableStateOf(medicalInfo?.medicalConditions ?: "") }
    var address by remember { mutableStateOf(medicalInfo?.address ?: "") }
    var emergencyNotes by remember { mutableStateOf(medicalInfo?.emergencyNotes ?: "") }
    
    // Update states when medicalInfo changes (e.g., loaded from database)
    LaunchedEffect(medicalInfo) {
        medicalInfo?.let { info ->
            fullName = info.fullName
            bloodType = info.bloodType
            allergies = info.allergies
            medications = info.medications
            medicalConditions = info.medicalConditions
            address = info.address
            emergencyNotes = info.emergencyNotes
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Atrás",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                
                Text(
                    text = "Información Médica",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = {
                    onSave(
                        MedicalInfo(
                            fullName = fullName,
                            bloodType = bloodType,
                            allergies = allergies,
                            medications = medications,
                            medicalConditions = medicalConditions,
                            address = address,
                            emergencyNotes = emergencyNotes
                        )
                    )
                }) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Guardar",
                        tint = Color(0xFF4CAF50)
                    )
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        Text(
                            text = "Esta información se mostrará en la pantalla de emergencia cuando se detecte un accidente, incluso con el móvil bloqueado.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                MedicalTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = "Nombre Completo",
                    icon = Icons.Default.Person
                )
                
                MedicalTextField(
                    value = bloodType,
                    onValueChange = { bloodType = it },
                    label = "Tipo de Sangre (ej: O+, A-, AB+)",
                    icon = Icons.Default.Favorite
                )
                
                MedicalTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = "Dirección",
                    icon = Icons.Default.Home,
                    maxLines = 2
                )
                
                MedicalTextField(
                    value = allergies,
                    onValueChange = { allergies = it },
                    label = "Alergias",
                    icon = Icons.Default.Warning,
                    maxLines = 3
                )
                
                MedicalTextField(
                    value = medications,
                    onValueChange = { medications = it },
                    label = "Medicamentos Actuales",
                    icon = Icons.Default.Add,
                    maxLines = 3
                )
                
                MedicalTextField(
                    value = medicalConditions,
                    onValueChange = { medicalConditions = it },
                    label = "Condiciones Médicas",
                    icon = Icons.Default.Info,
                    maxLines = 3
                )
                
                MedicalTextField(
                    value = emergencyNotes,
                    onValueChange = { emergencyNotes = it },
                    label = "Notas Adicionales",
                    icon = Icons.Default.Edit,
                    maxLines = 4
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun MedicalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = maxLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}
