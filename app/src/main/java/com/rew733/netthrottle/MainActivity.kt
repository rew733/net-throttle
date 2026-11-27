package com.rew733.netthrottle

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnToggleVpn: MaterialButton
    private lateinit var etSpeedInput: EditText
    private lateinit var btnApplySpeed: MaterialButton
    private lateinit var switchForegroundOnly: SwitchMaterial
    private lateinit var tvLiveStats: TextView
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var tvDiagnosticLog: TextView
    private lateinit var tabLayout: TabLayout

    // حاوية التبويب الأول والثاني
    private lateinit var appsContainer: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnDeselectAll: MaterialButton
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter

    // عناصر التبويب الثالث (المنافذ والبروتوكولات)
    private lateinit var portsContainer: ScrollView
    private lateinit var tvWebTcpLive: TextView
    private lateinit var tvQuicLive: TextView
    private lateinit var switchBlockQuic: SwitchMaterial
    private lateinit var tvCallsUdpLive: TextView
    private lateinit var switchAllowCallsUdp: SwitchMaterial
    private lateinit var etCallsPps: EditText
    private lateinit var tvOtherTcpLive: TextView
    private lateinit var etOtherTcpSpeed: EditText
    private lateinit var etMaxConns: EditText
    private lateinit var btnApplyPorts: MaterialButton

    private var currentSpeedKbps: Int = 60
    private var currentOtherTcpKbps: Int = 30
    private var currentBlockQuic: Boolean = true
    private var currentAllowCallsUdp: Boolean = true
    private var currentCallsPps: Int = 30
    private var currentMaxConns: Int = 3

    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiStatsUpdater = object : Runnable {
        override fun run() {
            val down = ThrottleVpnService.liveDownKbps
            val up = ThrottleVpnService.liveUpKbps
            val conns = ThrottleVpnService.liveConnections
            tvLiveStats.text = "تنزيل: $down KB/s | رفع: $up KB/s | اتصالات: $conns"
            tvDiagnosticLog.text = ThrottleVpnService.diagnosticStatus

            // تحديث العدادات الحية داخل تبويب المنافذ
            tvWebTcpLive.text = "الحي: ${ThrottleVpnService.liveWebTcpKbps} KB/s"
            tvQuicLive.text = "محظور: ${ThrottleVpnService.liveQuicBlockedCount}"
            tvCallsUdpLive.text = "الحي: ${ThrottleVpnService.liveCallsUdpKbps} KB/s"
            tvOtherTcpLive.text = "الحي: ${ThrottleVpnService.liveOtherTcpKbps} KB/s"

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
        switchForegroundOnly = findViewById(R.id.switchForegroundOnly)
        tvLiveStats = findViewById(R.id.tvLiveStats)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        tvDiagnosticLog = findViewById(R.id.tvDiagnosticLog)
        tabLayout = findViewById(R.id.tabLayout)

        appsContainer = findViewById(R.id.appsContainer)
        etSearch = findViewById(R.id.etSearch)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeselectAll = findViewById(R.id.btnDeselectAll)
        rvApps = findViewById(R.id.rvApps)

        portsContainer = findViewById(R.id.portsContainer)
        tvWebTcpLive = findViewById(R.id.tvWebTcpLive)
        tvQuicLive = findViewById(R.id.tvQuicLive)
        switchBlockQuic = findViewById(R.id.switchBlockQuic)
        tvCallsUdpLive = findViewById(R.id.tvCallsUdpLive)
        switchAllowCallsUdp = findViewById(R.id.switchAllowCallsUdp)
        etCallsPps = findViewById(R.id.etCallsPps)
        tvOtherTcpLive = findViewById(R.id.tvOtherTcpLive)
        etOtherTcpSpeed = findViewById(R.id.etOtherTcpSpeed)
        etMaxConns = findViewById(R.id.etMaxConns)
        btnApplyPorts = findViewById(R.id.btnApplyPorts)

        val prefs = getSharedPreferences("net_throttle_prefs", Context.MODE_PRIVATE)
        currentSpeedKbps = prefs.getInt("speed_kbps", 60).coerceAtLeast(1)
        currentOtherTcpKbps = prefs.getInt("other_tcp_kbps", 30).coerceAtLeast(1)
        currentBlockQuic = prefs.getBoolean("block_quic", true)
        currentAllowCallsUdp = prefs.getBoolean("allow_calls_udp", true)
        currentCallsPps = prefs.getInt("calls_pps", 30).coerceAtLeast(1)
        currentMaxConns = prefs.getInt("max_concurrent_conns", 3).coerceAtLeast(0)

        switchForegroundOnly.isChecked = prefs.getBoolean("foreground_only_mode", false)
        etSpeedInput.setText(currentSpeedKbps.toString())
        etOtherTcpSpeed.setText(currentOtherTcpKbps.toString())
        switchBlockQuic.isChecked = currentBlockQuic
        switchAllowCallsUdp.isChecked = currentAllowCallsUdp
        etCallsPps.setText(currentCallsPps.toString())
        etMaxConns.setText(currentMaxConns.toString())

        switchForegroundOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasUsageStatsPermission()) {
                switchForegroundOnly.isChecked = false
                Toast.makeText(
                    this,
                    "فعّل صلاحية الوصول إلى بيانات الاستخدام لتطبيق NetThrottle أولاً",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("foreground_only_mode", isChecked).apply()
            if (ThrottleVpnService.isRunning) {
                startThrottleService()
            }
        }

        adapter = AppAdapter {
            saveSelections()
        }
        adapter.throttledPackages.addAll(prefs.getStringSet("throttled_pkgs", emptySet()) ?: emptySet())
        adapter.blockedPackages.addAll(prefs.getStringSet("blocked_pkgs", emptySet()) ?: emptySet())

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        btnApplySpeed.setOnClickListener {
            val enteredText = etSpeedInput.text.toString().trim()
            val parsed = enteredText.toIntOrNull()
            if (parsed == null || parsed < 1) {
                Toast.makeText(this, "أدخل رقماً صحيحاً أكبر من 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentSpeedKbps = parsed
            prefs.edit().putInt("speed_kbps", currentSpeedKbps).apply()
            hideKeyboard(etSpeedInput)

            if (ThrottleVpnService.isRunning) {
                val intent = Intent(this, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_UPDATE_SPEED
                    putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
                }
                startService(intent)
            }
            Toast.makeText(this, "تم ضبط الحد الأساسي على $currentSpeedKbps KB/s", Toast.LENGTH_SHORT).show()
        }

        btnApplyPorts.setOnClickListener {
            val parsedOther = etOtherTcpSpeed.text.toString().trim().toIntOrNull()
            val parsedPps = etCallsPps.text.toString().trim().toIntOrNull()
            val parsedMaxConns = etMaxConns.text.toString().trim().toIntOrNull()
            if (parsedOther == null || parsedOther < 1 || parsedPps == null || parsedPps < 1 || parsedMaxConns == null || parsedMaxConns < 0) {
                Toast.makeText(this, "تأكد من إدخال أرقام صحيحة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentOtherTcpKbps = parsedOther
            currentCallsPps = parsedPps
            currentMaxConns = parsedMaxConns
            currentBlockQuic = switchBlockQuic.isChecked
            currentAllowCallsUdp = switchAllowCallsUdp.isChecked

            prefs.edit()
                .putInt("other_tcp_kbps", currentOtherTcpKbps)
                .putInt("calls_pps", currentCallsPps)
                .putInt("max_concurrent_conns", currentMaxConns)
                .putBoolean("block_quic", currentBlockQuic)
                .putBoolean("allow_calls_udp", currentAllowCallsUdp)
                .apply()

            hideKeyboard(etMaxConns)

            if (ThrottleVpnService.isRunning) {
                val intent = Intent(this, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_UPDATE_PORTS
                    putExtra(ThrottleVpnService.EXTRA_SPEED_KBPS, currentSpeedKbps)
                    putExtra(ThrottleVpnService.EXTRA_OTHER_TCP_KBPS, currentOtherTcpKbps)
                    putExtra(ThrottleVpnService.EXTRA_BLOCK_QUIC, currentBlockQuic)
                    putExtra(ThrottleVpnService.EXTRA_ALLOW_CALLS_UDP, currentAllowCallsUdp)
                    putExtra(ThrottleVpnService.EXTRA_CALLS_PPS, currentCallsPps)
                    putExtra(ThrottleVpnService.EXTRA_MAX_CONCURRENT_CONNS, currentMaxConns)
                }
                startService(intent)
            }
            Toast.makeText(this, "تم حفظ وتطبيق إعدادات المنافذ والاتصالات", Toast.LENGTH_SHORT).show()
        }

        btnCopyLog.setOnClickListener {
            val report = buildString {
                appendLine("=== تقرير NetThrottle ===")
                appendLine("الحد الأساسي (Web TCP): $currentSpeedKbps KB/s")
                appendLine("حظر QUIC (UDP 443): $currentBlockQuic (محظور: ${ThrottleVpnService.liveQuicBlockedCount})")
                appendLine("مكالمات UDP: $currentAllowCallsUdp ($currentCallsPps pps | الحي: ${ThrottleVpnService.liveCallsUdpKbps} KB/s)")
                appendLine("منافذ TCP الأخرى: $currentOtherTcpKbps KB/s (الحي: ${ThrottleVpnService.liveOtherTcpKbps} KB/s)")
                appendLine("الحد الأقصى للاتصالات المتزامنة: $currentMaxConns")
                appendLine("وضع التطبيق المفتوح فقط: ${switchForegroundOnly.isChecked}")
                appendLine("القياس الحي: ${tvLiveStats.text}")
                appendLine("السجل: ${ThrottleVpnService.diagnosticStatus}")
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
                val pos = tab?.position ?: 0
                if (pos == 2) {
                    appsContainer.visibility = View.GONE
                    portsContainer.visibility = View.VISIBLE
                } else {
                    portsContainer.visibility = View.GONE
                    appsContainer.visibility = View.VISIBLE
                    adapter.currentTab = pos
                }
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
                if (switchForegroundOnly.isChecked && !hasUsageStatsPermission()) {
                    Toast.makeText(
                        this,
                        "فعّل صلاحية الوصول إلى بيانات الاستخدام أولاً",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return@setOnClickListener
                }
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

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
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

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
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
            putExtra(ThrottleVpnService.EXTRA_OTHER_TCP_KBPS, currentOtherTcpKbps)
            putExtra(ThrottleVpnService.EXTRA_BLOCK_QUIC, switchBlockQuic.isChecked)
            putExtra(ThrottleVpnService.EXTRA_ALLOW_CALLS_UDP, switchAllowCallsUdp.isChecked)
            putExtra(ThrottleVpnService.EXTRA_CALLS_PPS, currentCallsPps)
            putExtra(ThrottleVpnService.EXTRA_MAX_CONCURRENT_CONNS, currentMaxConns)
            putExtra(ThrottleVpnService.EXTRA_FOREGROUND_ONLY_MODE, switchForegroundOnly.isChecked)
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
                    val pkg = appInfo.packageName
                    pkg != packageName &&
                            !ThrottleVpnService.isProtectedSystemPackage(pkg) &&
                            pm.getLaunchIntentForPackage(pkg) != null
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
