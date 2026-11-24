package id.ac.ceh

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import id.ac.ceh.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val hub by viewModels<HubViewModel>()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hub.onLocationPermissionResult()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
        val tabs = setOf(R.id.homeFragment, R.id.mapFragment, R.id.savedFragment, R.id.profileFragment)
        val bars = AppBarConfiguration(tabs)
        binding.bottomNav.setupWithNavController(nav)
        nav.addOnDestinationChangedListener { _, destination, args ->
            val picking = destination.id == R.id.mapFragment && args?.getBoolean(MapPick.MODE) == true
            val root = destination.id in tabs && !picking
            binding.bottomNav.isVisible = root
            binding.toolbar.title = if (picking) getString(R.string.pick_map) else destination.label
            binding.toolbar.navigationIcon = if (root) null else getDrawable(R.drawable.ic_hub_back)
            binding.toolbar.setNavigationContentDescription(R.string.go_back)
            binding.toolbar.setNavigationOnClickListener {
                if (picking) nav.popBackStack() else nav.navigateUp(bars)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.messages.collect { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                hub.permissionNeeded.collect { needed ->
                    if (needed && hub.beginLocationPermissionPrompt()) {
                        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hub.onLocationResumed()
    }

    override fun onPause() {
        hub.onLocationPaused()
        super.onPause()
    }

    override fun onStop() {
        hub.onLocationStopped()
        super.onStop()
    }
}

object MapPick {
    const val MODE = "pickMode"
    const val RESULT = "pickedCoordinates"
}
