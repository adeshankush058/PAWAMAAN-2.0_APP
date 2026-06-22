package com.example.pawamaan20.ui.screens

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pawamaan20.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

@Composable
fun AddDeviceScreen(onSuccess: () -> Unit) {
    var deviceId by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val auth = FirebaseAuth.getInstance()
    val dbUrl = "https://pawamaan-29da0-default-rtdb.asia-southeast1.firebasedatabase.app"
    val rtdb = FirebaseDatabase.getInstance(dbUrl)
    
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE3F2FD), Color.White)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_of_app),
                contentDescription = "Pawamaan Logo",
                modifier = Modifier.width(150.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Connect Your Device",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D47A1)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Enter the MAC ID of your sensing hardware",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("Device ID") },
                        placeholder = { Text("e.g. 88_13_BF_0D_61_5C") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                        singleLine = true,
                        enabled = !isLoading
                    )

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = statusMessage!!,
                            color = if (isError) Color.Red else Color(0xFF4CAF50),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            if (deviceId.isBlank()) {
                                statusMessage = "Please enter a Device ID"
                                isError = true
                                return@Button
                            }
                            
                            isLoading = true
                            statusMessage = "Verifying device..."
                            isError = false
                            
                            val uid = auth.currentUser?.uid
                            Log.d("FLOW", "UID: $uid")
                            Log.d("FLOW", "Device entered: $deviceId")

                            rtdb.reference.child("devices").child(deviceId)
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(snapshot: DataSnapshot) {
                                        Log.d("DEVICE", "Exists: ${snapshot.exists()}")
                                        Log.d("DEVICE", "Has info: ${snapshot.child("info").exists()}")
                                        Log.d("DEVICE", "Has data: ${snapshot.child("latest").exists()}")
                                        
                                        if (snapshot.exists() && snapshot.child("info").exists() && snapshot.child("latest").exists()) {
                                            statusMessage = "Device Found ✅"
                                            // Link device to user in RTDB
                                            rtdb.reference.child("users").child(uid!!)
                                                .child("devices").child(deviceId).setValue(true)
                                                .addOnCompleteListener { task ->
                                                    isLoading = false
                                                    if (task.isSuccessful) {
                                                        Log.d("FLOW", "Device linked")
                                                        onSuccess()
                                                    } else {
                                                        isError = true
                                                        statusMessage = "Linking failed: ${task.exception?.message}"
                                                    }
                                                }
                                        } else {
                                            isLoading = false
                                            isError = true
                                            statusMessage = "Device not found or not initialized yet"
                                        }
                                    }

                                    override fun onCancelled(error: DatabaseError) {
                                        isLoading = false
                                        isError = true
                                        statusMessage = "Error: ${error.message}"
                                    }
                                })
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1)),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Text("Connect Device", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
