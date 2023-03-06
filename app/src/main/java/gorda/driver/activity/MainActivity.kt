package gorda.driver.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.*
import com.bumptech.glide.Glide
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import gorda.driver.R
import gorda.driver.background.LocationService
import gorda.driver.databinding.ActivityMainBinding
import gorda.driver.interfaces.LocationUpdateInterface
import gorda.driver.location.LocationHandler
import gorda.driver.models.Driver
import gorda.driver.models.Service
import gorda.driver.services.firebase.Auth
import gorda.driver.services.network.NetworkMonitor
import gorda.driver.ui.MainViewModel
import gorda.driver.ui.driver.DriverUpdates
import gorda.driver.ui.service.LocationBroadcastReceiver
import gorda.driver.ui.service.dataclasses.LocationUpdates
import gorda.driver.utils.Constants
import gorda.driver.utils.Constants.Companion.LOCATION_EXTRA


@SuppressLint("UseSwitchCompatOrMaterialCode")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var preferences: SharedPreferences
    private lateinit var connectionBar: ProgressBar
    private var driver: Driver = Driver()
    private lateinit var switchConnect: Switch
    private lateinit var lastLocation: Location
    private val viewModel: MainViewModel by viewModels()
    private var locationService: Messenger? = null
    private var mBound: Boolean = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            locationService = Messenger(service)
            mBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            mBound = false
        }
    }
    private val locationBroadcastReceiver =
        LocationBroadcastReceiver(object : LocationUpdateInterface {
            override fun onUpdate(intent: Intent) {
                val extra: Location? = intent.getParcelableExtra(LOCATION_EXTRA)
                extra?.let { location ->
                    viewModel.updateLocation(location)
                }
            }
        })

    private lateinit var snackBar: Snackbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = getPreferences(MODE_PRIVATE)

        intent.getStringExtra(Constants.DRIVER_ID_EXTRA)?.let {
            viewModel.getDriver(it)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connectionBar = binding.root.findViewById(R.id.connectionBar)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_current_service, R.id.nav_home),
            drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        networkMonitor = NetworkMonitor(this) { isConnected ->
            onNetWorkChange(isConnected)
        }

        snackBar = Snackbar.make(
            binding.root,
            resources.getString(R.string.connection_lost),
            Snackbar.LENGTH_INDEFINITE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            snackBar.setTextColor(getColor(R.color.white))
        }

        navView.setNavigationItemSelectedListener { item ->
            if (item.itemId == R.id.logout) {
                driver.id?.let { viewModel.disconnect(driver) }
                Auth.logOut(this).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val intent = Intent(this, StartActivity::class.java)
                        startActivity(intent)
                    }
                }
            }
            NavigationUI.onNavDestinationSelected(item, navController)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        navController.addOnDestinationChangedListener { controller, destination, _ ->
            if (destination.id == R.id.nav_home) {
                if (viewModel.currentService.value != null) {
                    controller.navigate(R.id.nav_current_service)
                }
            } else if (destination.id == R.id.nav_apply) {
                if (viewModel.isNetWorkConnected.value == false) {
                    controller.navigate(R.id.nav_home)
                }
            }
        }

        this.switchConnect = binding.appBarMain.toolbar.findViewById(R.id.switchConnect)

        switchConnect.setOnClickListener {
            if (switchConnect.isChecked) {
                viewModel.connect(driver)
            } else {
                viewModel.disconnect(driver)
            }
        }

        viewModel.lastLocation.observe(this) { locationUpdate ->
            when (locationUpdate) {
                is LocationUpdates.LastLocation -> {
                    lastLocation = locationUpdate.location
                }
                else -> {}
            }
        }

        observeDriver(navView)


        viewModel.isNetWorkConnected.observe(this) {
            if (!it) {
                connectionBar.visibility = View.VISIBLE
                snackBar.show()
                viewModel.setConnectedLocal(false)
            }
            else {
                driver.id?.let { id -> viewModel.isConnected(id) }
                connectionBar.visibility = View.GONE
                snackBar.dismiss()
            }
        }

        viewModel.currentService.observe(this) { currentService ->
            currentService?.status?.let { status ->
                when (status) {
                    Service.STATUS_IN_PROGRESS -> {
                            navController.navigate(R.id.nav_current_service)
                    }
                    Service.STATUS_CANCELED -> {
                        viewModel.completeCurrentService()
                        if (navController.currentDestination?.id == R.id.nav_current_service)
                            navController.navigate(R.id.nav_home)
                    }
                    else -> {
                        if (navController.currentDestination?.id == R.id.nav_current_service)
                            navController.navigate(R.id.nav_home)
                        val editor: SharedPreferences.Editor = preferences.edit()
                        editor.putString(Constants.CURRENT_SERVICE_ID, null)
                        editor.apply()
                    }
                }
            }
        }
    }

    private fun onNetWorkChange(isConnected: Boolean) {
        viewModel.changeNetWorkStatus(isConnected)
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                locationBroadcastReceiver,
                IntentFilter(LocationBroadcastReceiver.ACTION_LOCATION_UPDATES)
            )
        LocationHandler.getLastLocation()?.let {
            it.addOnSuccessListener { loc ->
                if (loc != null) viewModel.updateLocation(loc)
            }
        }
        Intent(this, LocationService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_NOT_FOREGROUND)
        }

        viewModel.isThereCurrentService()
        networkMonitor.startMonitoring()
    }

    override fun onStop() {
        super.onStop()
        val editor: SharedPreferences.Editor = preferences.edit()
        editor.putString(Constants.CURRENT_SERVICE_ID, viewModel.currentService.value?.id)
        editor.apply()
        if (mBound) {
            this.unbindService(connection)
            mBound = false
            LocalBroadcastManager.getInstance(this).unregisterReceiver(locationBroadcastReceiver)
        }
        networkMonitor.stopMonitoring()
    }

    override fun onResume() {
        super.onResume()
        if (!LocationHandler.checkPermissions(this)) {
            showDisClosure()
        }
        if (!isLocationEnabled()) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }
    }

    private fun showDisClosure() {
        val builder = AlertDialog.Builder(this)
        builder.setIcon(R.drawable.ic_location_24)
        val layout: View = layoutInflater.inflate(R.layout.disclosure_layout, null)
        builder.setView(layout)
        builder.setPositiveButton(R.string.allow) { _, _ ->
            requestPermissions()
        }
        builder.setNegativeButton(R.string.disallow) { _, _ ->
            finish()
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.setOnShowListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(resources.getColor(R.color.primary_light, null))
                alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(resources.getColor(R.color.primary_light, null))
            }
        }
        alertDialog.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun setDrawerHeader(navView: NavigationView) {
        val header = navView.getHeaderView(0)
        val imageDrawer = header.findViewById<ImageView>(R.id.drawer_image)
        val nameDrawer = header.findViewById<TextView>(R.id.drawer_name)
        val emailDrawer = header.findViewById<TextView>(R.id.drawer_email)

        nameDrawer.text = driver.name
        emailDrawer.text = driver.email
        driver.photoUrl?.let { url ->
            Glide
                .with(this)
                .load(url)
                .placeholder(R.mipmap.ic_profile)
                .into(imageDrawer)
        }
    }

    private fun observeDriver(navView: NavigationView) {
        viewModel.driverStatus.observe(this) { driverUpdates ->
            when (driverUpdates) {
                is DriverUpdates.IsConnected -> {
                    switchConnect.isChecked = driverUpdates.connected
                    if (driverUpdates.connected) {
                        startLocationService()
                        switchConnect.setText(R.string.status_connected)
                    } else {
                        switchConnect.setText(R.string.status_disconnected)
                        stopLocationService()
                    }
                }
                is DriverUpdates.Connecting -> {
                    if (driverUpdates.connecting) {
                        switchConnect.setText(R.string.status_connecting)
                    }
                }
                else -> {}
            }
        }

        viewModel.driver.observe(this) {
            when (it) {
                is Driver -> {
                    this.driver = it
                    switchConnect.isEnabled = true
                    setDrawerHeader(navView)
                    viewModel.isConnected(it.id!!)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LocationHandler.PERMISSION_REQUEST_ACCESS_LOCATION) {
            if (grantResults.isEmpty() && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                finish()
            }
        }
    }

    private fun startLocationService() {
        Intent(this, LocationService::class.java).also { intent ->
            intent.putExtra(Driver.DRIVER_KEY, this.driver.id)
            applicationContext.startService(intent)
            this.bindService(intent, connection, BIND_NOT_FOREGROUND)
            mBound = true
        }
    }

    private fun stopLocationService() {
        if (mBound) {
            this.unbindService(connection)
            val msg: Message = Message.obtain(null, LocationService.STOP_SERVICE_MSG, 0, 0)
            locationService?.send(msg)
            mBound = false
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ), LocationHandler.PERMISSION_REQUEST_ACCESS_LOCATION
        )
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun onBackPressed() {
        if (navController.currentDestination != null && navController.currentDestination?.id != R.id.nav_home) {
            super.onBackPressed()
        }
    }
}
