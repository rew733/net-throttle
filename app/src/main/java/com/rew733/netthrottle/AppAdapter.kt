package com.rew733.netthrottle

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val uid: Int
)

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

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = targetSet.contains(app.packageName)

        holder.itemView.setOnClickListener {
            holder.checkBox.isChecked = !holder.checkBox.isChecked
        }

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
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
        val ivAppIcon: ImageView = findViewByType(view, ImageView::class.java)!!
        val checkBox: CheckBox = findViewByType(view, CheckBox::class.java)!!
        val tvAppName: TextView
        val tvPackageName: TextView

        init {
            val textViews = mutableListOf<TextView>()
            collectTextViews(view, textViews)
            tvAppName = textViews.getOrElse(0) { TextView(view.context) }
            tvPackageName = textViews.getOrElse(1) { tvAppName }
        }

        companion object {
            private fun <T : View> findViewByType(root: View, clazz: Class<T>): T? {
                if (clazz.isInstance(root)) return clazz.cast(root)
                if (root is ViewGroup) {
                    for (i in 0 until root.childCount) {
                        val found = findViewByType(root.getChildAt(i), clazz)
                        if (found != null) return found
                    }
                }
                return null
            }

            private fun collectTextViews(root: View, out: MutableList<TextView>) {
                if (root is TextView && root !is CheckBox) {
                    out.add(root)
                } else if (root is ViewGroup) {
                    for (i in 0 until root.childCount) {
                        collectTextViews(root.getChildAt(i), out)
                    }
                }
            }
        }
    }
}
