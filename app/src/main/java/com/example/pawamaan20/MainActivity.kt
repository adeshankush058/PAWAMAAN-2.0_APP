package com.example.pawamaan20

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pawamaan20.ui.screens.AddDeviceScreen
import com.example.pawamaan20.ui.screens.HomeScreen
import com.example.pawamaan20.ui.screens.LoginScreen
import com.example.pawamaan20.ui.screens.SignUpScreen
import com.example.pawamaan20.ui.screens.SplashScreen
import com.example.pawamaan20.ui.screens.TripDetailScreen
import com.example.pawamaan20.ui.theme.PAWAMAAN20Theme
import com.example.pawamaan20.viewmodel.DeviceViewModel
import com.google.firebase.auth.FirebaseAuth
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PAWAMAAN20Theme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current
    
    // Shared ViewModel for devices
    val deviceViewModel: DeviceViewModel = viewModel()
    
    // Initialize listeners for context-dependent tasks like CSV saving
    deviceViewModel.initListeners(context)
    
    NavHost(
        navController = navController,
        startDestination = "splash"
    ) {
        composable("splash") {
            SplashScreen(onNavigateToLogin = {
                // AUTO-LOGIN CHECK: Decide where to go after splash
                if (auth.currentUser != null) {
                    navController.navigate("home") {
                        popUpTo("splash") { inclusive = true }
                    }
                } else {
                    navController.navigate("login") {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            })
        }
        
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onSignUpClick = {
                    navController.navigate("signup")
                }
            )
        }
        
        composable("signup") {
            SignUpScreen(
                onNavigateToHome = {
                    navController.navigate("home") {
                        popUpTo("signup") { inclusive = true }
                    }
                },
                onLoginClick = {
                    navController.popBackStack()
                }
            )
        }
        
        composable("add_device") {
            AddDeviceScreen(
                onSuccess = {
                    navController.navigate("home") {
                        popUpTo("add_device") { inclusive = true }
                    }
                }
            )
        }
        
        composable("home") {
            HomeScreen(
                deviceViewModel = deviceViewModel,
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToAddDevice = {
                    navController.navigate("add_device") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onNavigateToTripDetail = { path ->
                    val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.toString())
                    navController.navigate("trip_detail/$encodedPath")
                }
            )
        }

        composable(
            route = "trip_detail/{csvPath}",
            arguments = listOf(navArgument("csvPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("csvPath") ?: ""
            val decodedPath = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.toString())
            TripDetailScreen(
                csvPath = decodedPath,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
