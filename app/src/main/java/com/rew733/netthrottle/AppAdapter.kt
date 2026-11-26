package com.rew733.netthrottle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private var allApps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()

    val throttledPackages = mutableSetOf<String>()
    val blockedPackages = mutableSetOf<String>()

    var currentTab: Int = 0
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private var currentQuery: String = ""

    fun submitList(apps: List<AppInfo>) {
        allApps = apps
        applyFilter(currentQuery)
    }

    fun applyFilter(query: String) {
        currentQuery = query
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            val q = query.trim().lowercase()
            allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAllVisible() {
        val targetSet = if (currentTab == 0) throttledPackages else blockedPackages
        for (app in filteredApps) {
            targetSet.add(app.packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun deselectAllVisible() {
        val targetSet = if (currentTab == 0) throttledPackages else blockedPackages
        for (app in filteredApps) {
            targetSet.remove(app.packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.tvAppName.text = app.name
        holder.tvPackageName.text = app.packageName
        holder.ivAppIcon.setImageDrawable(app.icon)

        val targetSet = if (currentTab == 0) throttledPackages else blockedPackages

        holder.cbAppSelect.setOnCheckedChangeListener(null)
        holder.cbAppSelect.isChecked = targetSet.contains(app.packageName)

        holder.itemView.setOnClickListener {
            holder.cbAppSelect.isChecked = !holder.cbAppSelect.isChecked
        }

        holder.cbAppSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                targetSet.add(app.packageName)
            } else {
                targetSet.remove(app.packageName)
            }
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
        val cbAppSelect: CheckBox = view.findViewById(R.id.cbAppSelect)
    }
}
