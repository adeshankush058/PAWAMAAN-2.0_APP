package com.example.pawamaan20.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pawamaan20.R
import com.example.pawamaan20.viewmodel.DeviceViewModel
import com.example.pawamaan20.viewmodel.DeviceItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(deviceViewModel: DeviceViewModel, onLogout: () -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val user = auth.currentUser
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // User Info State
    var userName by remember { mutableStateOf("Loading...") }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // Device Management State
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    var selectedDeviceToDelete by remember { mutableStateOf<DeviceItem?>(null) }

    // PART 2: STATE MANAGEMENT
    var showDialog by remember { mutableStateOf(false) }
    var dialogType by remember { mutableStateOf("") }

    // Fetch user name from Firestore
    LaunchedEffect(user?.uid) {
        user?.uid?.let { uid ->
            db.collection("users").document(uid).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        userName = document.getString("name") ?: "User"
                    } else {
                        userName = user.email?.split("@")?.get(0) ?: "User"
                    }
                }
                .addOnFailureListener {
                    userName = user.email?.split("@")?.get(0) ?: "User"
                }
        }
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE3F2FD), Color.White)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Branding Section
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_of_app),
                    contentDescription = "Pawamaan Logo",
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(top = 16.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Profile",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D47A1)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 1. USER ACCOUNT CARD
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFFE3F2FD), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color(0xFF0D47A1)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = user?.email ?: "No Email",
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "ID: ${user?.uid?.take(8)}...",
                                fontSize = 12.sp,
                                color = Color.LightGray
                            )
                        }
                        
                        TextButton(onClick = { 
                            scope.launch { snackbarHostState.showSnackbar("Edit profile coming soon") }
                        }) {
                            Text("Edit", color = Color(0xFF0D47A1), fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. MY DEVICES SECTION (Secondary Devices Only)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "My Devices",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D47A1),
                        fontSize = 16.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    if (deviceViewModel.secondaryDevices.isEmpty()) {
                        Text(
                            text = "No secondary devices added yet",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    } else {
                        for (device in deviceViewModel.secondaryDevices) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF4CAF50), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = device.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(text = device.id, color = Color.Gray, fontSize = 12.sp)
                                }
                                IconButton(onClick = { selectedDeviceToDelete = device }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Device",
                                        tint = Color(0xFFD32F2F)
                                    )
                                }
                            }
                            HorizontalDivider(color = Color(0xFFF5F5F5))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showAddDeviceDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1))
                    ) {
                        Text("➕ Add Device")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. QUICK ACTIONS SECTION
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "Quick Actions",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D47A1)
                    )
                    
                    // Existing items
                    SettingsItem(icon = Icons.Default.Notifications, label = "Notification Settings", onClick = { scope.launch { snackbarHostState.showSnackbar("Notification settings coming soon") } })
                    SettingsItem(icon = Icons.Default.AutoGraph, label = "Prediction Settings", onClick = { scope.launch { snackbarHostState.showSnackbar("Prediction settings coming soon") } })
                    SettingsItem(icon = Icons.Default.Download, label = "Download My Data", onClick = { 
                        Toast.makeText(context, "Preparing download...", Toast.LENGTH_SHORT).show()
                        // This triggers the CSV download logic we built earlier
                    })
                    
                    // PART 1 & 3: ADD NEW OPTIONS + CLICK HANDLING
                    SettingsItem(icon = Icons.AutoMirrored.Filled.HelpCenter, label = "Help & Support", onClick = { 
                        dialogType = "help"
                        showDialog = true
                    })
                    SettingsItem(icon = Icons.Default.Email, label = "Contact Us", onClick = { 
                        dialogType = "contact"
                        showDialog = true
                    })
                    SettingsItem(icon = Icons.Default.Info, label = "About Device", onClick = { 
                        dialogType = "device"
                        showDialog = true
                    })
                    SettingsItem(icon = Icons.Default.Science, label = "About Lab", onClick = { 
                        dialogType = "lab"
                        showDialog = true
                    })
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 4. LOGOUT SECTION
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Logout", fontWeight = FontWeight.Bold, color = Color.White)
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // PART 4: DIALOG UI
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    text = when(dialogType) {
                        "help" -> "Help & Support"
                        "contact" -> "Contact Us"
                        "device" -> "Device Information"
                        "lab" -> "About Lab"
                        else -> ""
                    },
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0D47A1)
                )
            },
            text = {
                Column {
                    when(dialogType) {
                        "help" -> {
                            Text("• Make sure device is connected to WiFi")
                            Text("• Ensure correct Device ID is entered")
                            Text("• Check sensor power supply")
                            Text("• Restart app if data not updating")
                        }
                        "contact" -> {
                            Text("For any queries, contact us:Lab: +91-755-269-2668")

                            Spacer(modifier = Modifier.height(8.dp))
                            // PART 5: EMAIL ACTION
                            Text(
                                text = "Email: adesh22@iiserb.ac.in",
                                color = Color(0xFF0D47A1),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:adesh22@iiserb.ac.in")
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        "device" -> {
                            Text("Indoor Device monitors:", fontWeight = FontWeight.Bold)
                            Text("• AQI")
                            Text("• PM2.5 / PM10")
                            Text("• CO2")
                            Text("• Temperature")
                            Text("• Humidity")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Portable Device tracks:", fontWeight = FontWeight.Bold)
                            Text("• AQI with location")
                            Text("• Speed and movement")
                        }
                        "lab" -> {
                            Text("Developed by:", fontWeight = FontWeight.Bold)
                            Text("Sixth Sense Lab")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Focused on IoT-based environmental sensing and smart analytics solutions and Our goal is to build intelligent systems that bridge hardware sensing with real-world impact")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("OK", color = Color(0xFF0D47A1), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAddDeviceDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDeviceDialog = false },
            onAdd = { name, id ->
                deviceViewModel.addDevice(name, id) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) showAddDeviceDialog = false
                }
            }
        )
    }

    if (selectedDeviceToDelete != null) {
        AlertDialog(
            onDismissRequest = { selectedDeviceToDelete = null },
            title = { Text("Remove Device") },
            text = { Text("Are you sure you want to remove this device?") },
            confirmButton = {
                TextButton(onClick = {
                    selectedDeviceToDelete?.let { device ->
                        deviceViewModel.deleteDevice(device.id) { success, message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            selectedDeviceToDelete = null
                        }
                    }
                }) {
                    Text("Remove", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedDeviceToDelete = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout?") },
            text = { Text("Are you sure you want to logout from Pawamaan 2.0?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    auth.signOut()
                    onLogout()
                }) {
                    Text("Logout", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun AddDeviceDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Device") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, 
                    onValueChange = { name = it }, 
                    label = { Text("Device Name") },
                    placeholder = { Text("e.g. My Outdoor Sensor") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = id, 
                    onValueChange = { id = it }, 
                    label = { Text("Device MAC ID") },
                    placeholder = { Text("e.g. 88_13_BF_0D_61_5C") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, id) }) {
                Text("Connect Device")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = Color(0xFFF5F5F5))
    }
}
