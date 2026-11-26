package com.rew733.netthrottle

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("net_throttle_prefs", Context.MODE_PRIVATE) }
    private val selectedPackages = mutableSetOf<String>()
    private var currentSpeedKbps = 150

    private lateinit var adapter: AppAdapter
    private lateinit var btnToggleVpn: MaterialButton
    private lateinit var tvSpeedValue: TextView
    private lateinit var sliderSpeed: Slider

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startThrottleService()
        } else {
            Toast.makeText(this, "يجب الموافقة على إذن VPN لتحديد السرعة", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedPackages.addAll(prefs.getStringSet("selected_pkgs", emptySet()) ?: emptySet())
        currentSpeedKbps = prefs.getInt("speed_kbps", 150)

        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        sliderSpeed = findViewById(R.id.sliderSpeed)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        val etSearch = findViewById<EditText>(R.id.etSearch)
        val btnSelectAll = findViewById<Button>(R.id.btnSelectAll)
        val btnDeselectAll = findViewById<Button>(R.id.btnDeselectAll)
        val rvApps = findViewById<RecyclerView>(R.id.rvApps)

        sliderSpeed.value = currentSpeedKbps.toFloat().coerceIn(10f, 2000f)
        tvSpeedValue.text = "$currentSpeedKbps KB/s"

        sliderSpeed.addOnChangeListener { _, value, fromUser ->
            currentSpeedKbps = value.toInt()
            tvSpeedValue.text = "$currentSpeedKbps KB/s"
            prefs.edit().putInt("speed_kbps", currentSpeedKbps).apply()

            if (fromUser && ThrottleVpnService.isRunning) {
                val updateIntent = Intent(this, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_UPDATE_SPEED
                    putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
                }
                startService(updateIntent)
            }
        }

        adapter = AppAdapter(selectedPackages) {
            prefs.edit().putStringSet("selected_pkgs", selectedPackages.toSet()).apply()
        }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSelectAll.setOnClickListener { adapter.selectAllVisible() }
        btnDeselectAll.setOnClickListener { adapter.deselectAll() }

        btnToggleVpn.setOnClickListener {
            if (ThrottleVpnService.isRunning) {
                stopThrottleService()
            } else {
                if (selectedPackages.isEmpty()) {
                    Toast.makeText(this, "اختر تطبيقاً واحداً على الأقل", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prepareAndStartVpn()
            }
        }

        requestNotificationPermissionIfNeeded()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = packages.mapNotNull { appInfo ->
                if (appInfo.packageName == packageName) return@mapNotNull null
                val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (!isUserApp) return@mapNotNull null

                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = pm.getApplicationIcon(appInfo)
                )
            }.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                adapter.submitList(appList)
            }
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startThrottleService()
        }
    }

    private fun startThrottleService() {
        val intent = Intent(this, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_START
            putStringArrayListExtra(ThrottleVpnService.EXTRA_PACKAGES, ArrayList(selectedPackages))
            putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
        }
        ContextCompat.startForegroundService(this, intent)
        btnToggleVpn.postDelayed({ updateButtonState() }, 300)
    }

    private fun stopThrottleService() {
        val intent = Intent(this, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_STOP
        }
        startService(intent)
        btnToggleVpn.postDelayed({ updateButtonState() }, 300)
    }

    private fun updateButtonState() {
        if (ThrottleVpnService.isRunning) {
            btnToggleVpn.setText(R.string.stop_vpn)
        } else {
            btnToggleVpn.setText(R.string.start_vpn)
        }
    }
}
