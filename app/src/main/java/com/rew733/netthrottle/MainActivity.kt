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
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleVpn: MaterialButton
    private lateinit var tvSpeedValue: TextView
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var tabLayout: TabLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnDeselectAll: MaterialButton
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter

    private var currentSpeedKbps: Int = 100

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startThrottleService()
        } else {
            Toast.makeText(this, "يجب منح صلاحية VPN للتشغيل", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)
        tabLayout = findViewById(R.id.tabLayout)
        etSearch = findViewById(R.id.etSearch)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeselectAll = findViewById(R.id.btnDeselectAll)
        rvApps = findViewById(R.id.rvApps)

        val prefs = getSharedPreferences("net_throttle_prefs", Context.MODE_PRIVATE)
        currentSpeedKbps = prefs.getInt("speed_kbps", 100).coerceIn(10, 1000)

        adapter = AppAdapter {
            saveSelections()
        }
        adapter.throttledPackages.addAll(prefs.getStringSet("throttled_pkgs", emptySet()) ?: emptySet())
        adapter.blockedPackages.addAll(prefs.getStringSet("blocked_pkgs", emptySet()) ?: emptySet())

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        seekBarSpeed.progress = currentSpeedKbps
        tvSpeedValue.text = "$currentSpeedKbps KB/s"

        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSpeedKbps = progress.coerceAtLeast(10)
                tvSpeedValue.text = "$currentSpeedKbps KB/s"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt("speed_kbps", currentSpeedKbps).apply()
                if (ThrottleVpnService.isRunning) {
                    val intent = Intent(this@MainActivity, ThrottleVpnService::class.java).apply {
                        action = ThrottleVpnService.ACTION_UPDATE_SPEED
                        putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
                    }
                    startService(intent)
                }
            }
        })

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                adapter.currentTab = tab?.position ?: 0
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSelectAll.setOnClickListener {
            adapter.selectAllVisible()
        }

        btnDeselectAll.setOnClickListener {
            adapter.deselectAllVisible()
        }

        btnToggleVpn.setOnClickListener {
            if (ThrottleVpnService.isRunning) {
                val intent = Intent(this, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_STOP
                }
                startService(intent)
                btnToggleVpn.postDelayed({ updateToggleButton() }, 300)
            } else {
                prepareAndStartVpn()
            }
        }

        requestNotificationPermissionIfNeeded()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updateToggleButton()
    }

    private fun saveSelections() {
        val prefs = getSharedPreferences("net_throttle_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("throttled_pkgs", adapter.throttledPackages.toSet())
            .putStringSet("blocked_pkgs", adapter.blockedPackages.toSet())
            .apply()
    }

    private fun updateToggleButton() {
        if (ThrottleVpnService.isRunning) {
            btnToggleVpn.text = "إيقاف"
            btnToggleVpn.setBackgroundColor(0xFFE53935.toInt())
        } else {
            btnToggleVpn.text = "تشغيل"
            btnToggleVpn.setBackgroundColor(0xFF1E88E5.toInt())
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun prepareAndStartVpn() {
        if (adapter.throttledPackages.isEmpty() && adapter.blockedPackages.isEmpty()) {
            Toast.makeText(this, "حدد تطبيقاً واحداً على الأقل للتحديد أو الحظر", Toast.LENGTH_SHORT).show()
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startThrottleService()
        }
    }

    private fun startThrottleService() {
        val intent = Intent(this, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_START
            putStringArrayListExtra(ThrottleVpnService.EXTRA_PACKAGES, ArrayList(adapter.throttledPackages))
            putStringArrayListExtra(ThrottleVpnService.EXTRA_BLOCKED_PACKAGES, ArrayList(adapter.blockedPackages))
            putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
        }
        ContextCompat.startForegroundService(this, intent)
        btnToggleVpn.postDelayed({ updateToggleButton() }, 400)
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }

                packages.filter { appInfo ->
                    appInfo.packageName != packageName &&
                            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            pm.getLaunchIntentForPackage(appInfo.packageName) != null
                }.map { appInfo ->
                    AppInfo(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName,
                        icon = pm.getApplicationIcon(appInfo),
                        uid = appInfo.uid
                    )
                }.sortedBy { it.name.lowercase() }
            }
            adapter.submitList(apps)
        }
    }
}
