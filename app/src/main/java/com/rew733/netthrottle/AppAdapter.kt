package com.rew733.netthrottle

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class AppAdapter(
    private val selectedPackages: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private var fullList: List<AppItem> = emptyList()
    private var filteredList: List<AppItem> = emptyList()
    private var currentQuery: String = ""

    fun submitList(apps: List<AppItem>) {
        fullList = apps
        filter(currentQuery)
    }

    fun filter(query: String) {
        currentQuery = query.trim().lowercase()
        filteredList = if (currentQuery.isEmpty()) {
            fullList
        } else {
            fullList.filter {
                it.name.lowercase().contains(currentQuery) ||
                        it.packageName.lowercase().contains(currentQuery)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAllVisible() {
        filteredList.forEach { selectedPackages.add(it.packageName) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun deselectAll() {
        selectedPackages.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = filteredList[position]
        holder.ivIcon.setImageDrawable(item.icon)
        holder.tvName.text = item.name
        holder.tvPackage.text = item.packageName

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = selectedPackages.contains(item.packageName)

        holder.itemView.setOnClickListener {
            holder.cbSelect.isChecked = !holder.cbSelect.isChecked
        }

        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = filteredList.size

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelectApp)
    }
}
