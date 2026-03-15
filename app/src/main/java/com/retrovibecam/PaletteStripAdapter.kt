package com.retrovibecam

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PaletteStripAdapter(
    private val palettes: List<Palette>,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<PaletteStripAdapter.ViewHolder>() {

    private var selectedIndex = 0

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val swatchView: PaletteSwatchView = view.findViewById(R.id.swatchView)
        val tvName: TextView = view.findViewById(R.id.tvPaletteName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.palette_swatch_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val palette = palettes[position]
        holder.swatchView.colors = palette.colors.map { c ->
            Color.rgb(c[0].toInt(), c[1].toInt(), c[2].toInt())
        }
        holder.tvName.text = palette.name
        val selected = position == selectedIndex
        holder.itemView.isSelected = selected
        holder.tvName.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val prev = selectedIndex
            selectedIndex = pos
            notifyItemChanged(prev)
            notifyItemChanged(pos)
            onSelect(pos)
        }
    }

    override fun getItemCount(): Int = palettes.size
}
