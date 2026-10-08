package fr.tear36.blus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import fr.tear36.blus.ui.BlusViewModel
import fr.tear36.blus.ui.screens.MapTab
import fr.tear36.blus.ui.BlusTheme

class MainActivity : ComponentActivity() {

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) requestPosition()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private var pendingVm: BlusViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BlusTheme {
                val vm: BlusViewModel = viewModel()
                pendingVm = vm

                val granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

                LaunchedEffect(Unit) {
                    // Favourite-stop alerts are useless without a notification channel the
                    // user is allowed to post to.
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (granted) requestPosition() else askLocationPermission()
                }

                MapTab(vm = vm, onRequestLocation = { askLocationPermission() })
            }
        }
    }

    private fun askLocationPermission() {
        requestLocation.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun requestPosition() {
        val vm = pendingVm ?: return
        val client = LocationServices.getFusedLocationProviderClient(this)
        vm.bindLocation(client)
    }
}
