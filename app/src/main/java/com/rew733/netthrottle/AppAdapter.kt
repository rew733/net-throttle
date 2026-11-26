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
    private var searchQuery: String = ""

    // 0 = تبويب تحديد السرعة ، 1 = تبويب حظر البيانات
    var currentTab: Int = 0
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    val throttledPackages = mutableSetOf<String>()
    val blockedPackages = mutableSetOf<String>()

    fun submitList(apps: List<AppInfo>) {
        allApps = apps
        applyFilter(searchQuery)
    }

    fun applyFilter(query: String) {
        searchQuery = query.trim()
        filteredApps = if (searchQuery.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAllVisible() {
        val targetSet = if (currentTab == 0) throttledPackages else blockedPackages
        val oppositeSet = if (currentTab == 0) blockedPackages else throttledPackages
        for (app in filteredApps) {
            targetSet.add(app.packageName)
            oppositeSet.remove(app.packageName)
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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.tvName?.text = app.name
        holder.tvPkg?.text = app.packageName
        holder.ivIcon?.setImageDrawable(app.icon)

        val targetSet = if (currentTab == 0) throttledPackages else blockedPackages
        val oppositeSet = if (currentTab == 0) blockedPackages else throttledPackages

        holder.cbSelected?.setOnCheckedChangeListener(null)
        holder.cbSelected?.isChecked = targetSet.contains(app.packageName)

        val toggleAction = {
            val newState = !targetSet.contains(app.packageName)
            if (newState) {
                targetSet.add(app.packageName)
                oppositeSet.remove(app.packageName)
            } else {
                targetSet.remove(app.packageName)
            }
            holder.cbSelected?.isChecked = newState
            onSelectionChanged()
        }

        holder.itemView.setOnClickListener { toggleAction() }
        holder.cbSelected?.setOnClickListener { toggleAction() }
    }

    override fun getItemCount(): Int = filteredApps.size

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView? = findFirstViewOfType(view, ImageView::class.java)
        val cbSelected: CheckBox? = findFirstViewOfType(view, CheckBox::class.java)
        val tvName: TextView?
        val tvPkg: TextView?

        init {
            val textViews = mutableListOf<TextView>()
            collectTextViews(view, textViews)
            tvName = textViews.getOrNull(0)
            tvPkg = textViews.getOrNull(1)
        }

        companion object {
            private fun <T : View> findFirstViewOfType(root: View, clazz: Class<T>): T? {
                if (clazz.isInstance(root)) return clazz.cast(root)
                if (root is ViewGroup) {
                    for (i in 0 until root.childCount) {
                        val found = findFirstViewOfType(root.getChildAt(i), clazz)
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
