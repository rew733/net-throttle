package com.rew733.netthrottle

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
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
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnToggleVpn: MaterialButton
    private lateinit var etSpeedInput: EditText
    private lateinit var btnApplySpeed: MaterialButton
    private lateinit var tvLiveStats: TextView
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var tvDiagnosticLog: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnDeselectAll: MaterialButton
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter

    private var currentSpeedKbps: Int = 100
    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiStatsUpdater = object : Runnable {
        override fun run() {
            val down = ThrottleVpnService.liveDownKbps
            val up = ThrottleVpnService.liveUpKbps
            val conns = ThrottleVpnService.liveConnections
            tvLiveStats.text = "تنزيل: $down KB/s | رفع: $up KB/s | اتصالات: $conns"
            tvDiagnosticLog.text = ThrottleVpnService.diagnosticStatus
            updateToggleButton()
            uiHandler.postDelayed(this, 1000L)
        }
    }

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

        btnRefresh = findViewById(R.id.btnRefresh)
        btnToggleVpn = findViewById(R.id.btnToggleVpn)
        etSpeedInput = findViewById(R.id.etSpeedInput)
        btnApplySpeed = findViewById(R.id.btnApplySpeed)
        tvLiveStats = findViewById(R.id.tvLiveStats)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        tvDiagnosticLog = findViewById(R.id.tvDiagnosticLog)
        tabLayout = findViewById(R.id.tabLayout)
        etSearch = findViewById(R.id.etSearch)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeselectAll = findViewById(R.id.btnDeselectAll)
        rvApps = findViewById(R.id.rvApps)

        val prefs = getSharedPreferences("net_throttle_prefs", Context.MODE_PRIVATE)
        currentSpeedKbps = prefs.getInt("speed_kbps", 100).coerceAtLeast(1)

        adapter = AppAdapter {
            saveSelections()
        }
        adapter.throttledPackages.addAll(prefs.getStringSet("throttled_pkgs", emptySet()) ?: emptySet())
        adapter.blockedPackages.addAll(prefs.getStringSet("blocked_pkgs", emptySet()) ?: emptySet())

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        etSpeedInput.setText(currentSpeedKbps.toString())

        btnApplySpeed.setOnClickListener {
            val enteredText = etSpeedInput.text.toString().trim()
            val parsed = enteredText.toIntOrNull()
            if (parsed == null || parsed < 1) {
                Toast.makeText(this, "أدخل رقماً صحيحاً أكبر من 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentSpeedKbps = parsed
            prefs.edit().putInt("speed_kbps", currentSpeedKbps).apply()

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSpeedInput.windowToken, 0)

            if (ThrottleVpnService.isRunning) {
                val intent = Intent(this, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_UPDATE_SPEED
                    putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
                }
                startService(intent)
            }
            Toast.makeText(this, "تم ضبط السرعة على $currentSpeedKbps KB/s", Toast.LENGTH_SHORT).show()
        }

        btnCopyLog.setOnClickListener {
            val report = buildString {
                appendLine("=== تقرير NetThrottle ===")
                appendLine("السرعة المحددة: $currentSpeedKbps KB/s")
                appendLine("القياس الحي: ${tvLiveStats.text}")
                appendLine("السجل: ${ThrottleVpnService.diagnosticStatus}")
                appendLine("تطبيقات التحديد (${adapter.throttledPackages.size}): ${adapter.throttledPackages.joinToString()}")
                appendLine("تطبيقات الحظر (${adapter.blockedPackages.size}): ${adapter.blockedPackages.joinToString()}")
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("NetThrottle Log", report))
            Toast.makeText(this, "تم نسخ السجل التشخيصي", Toast.LENGTH_SHORT).show()
        }

        btnRefresh.setOnClickListener {
            loadInstalledApps()
            if (ThrottleVpnService.isRunning) {
                val intent = Intent(this, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_REFRESH
                }
                startService(intent)
                Toast.makeText(this, "تم تحديث الاتصالات وتفريغ الذاكرة", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "تم تحديث قائمة التطبيقات", Toast.LENGTH_SHORT).show()
            }
        }

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
                val typed = etSpeedInput.text.toString().trim().toIntOrNull()
                if (typed != null && typed >= 1) {
                    currentSpeedKbps = typed
                    prefs.edit().putInt("speed_kbps", currentSpeedKbps).apply()
                }
                prepareAndStartVpn()
            }
        }

        requestNotificationPermissionIfNeeded()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updateToggleButton()
        uiHandler.removeCallbacks(uiStatsUpdater)
        uiHandler.post(uiStatsUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiStatsUpdater)
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
