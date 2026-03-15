package com.retrovibecam

import android.content.Context
import android.graphics.Color

const val PALETTE_SHADER_SIZE = 16

// Each color is [r, g, b] in 0-255 range, matching the ColourDistance function.
data class Palette(val name: String, val colors: Array<FloatArray>, val passthrough: Boolean = false) {
    // Always returns exactly PALETTE_SHADER_SIZE * 3 floats, padding with the last color.
    fun toFlat(): FloatArray {
        val flat = FloatArray(PALETTE_SHADER_SIZE * 3)
        for (i in 0 until PALETTE_SHADER_SIZE) {
            val color = colors[minOf(i, colors.size - 1)]
            flat[i * 3 + 0] = color[0]
            flat[i * 3 + 1] = color[1]
            flat[i * 3 + 2] = color[2]
        }
        return flat
    }
}

// Loads palettes from res/values/palettes.xml.
// Each palette needs a palette_label_<id> string and palette_colors_<id> string-array.
// An empty color array is treated as passthrough (no filter).
fun loadPalettes(context: Context): List<Palette> {
    val res = context.resources
    val pkg = context.packageName
    val ids = res.getStringArray(R.array.palette_ids)
    return ids.map { id ->
        val nameRes = res.getIdentifier("palette_label_$id", "string", pkg)
        val name = res.getString(nameRes)
        val colorsRes = res.getIdentifier("palette_colors_$id", "array", pkg)
        val hexColors = res.getStringArray(colorsRes)
        if (hexColors.isEmpty()) {
            Palette(name, arrayOf(), passthrough = true)
        } else {
            val colors = hexColors.map { hex ->
                val c = Color.parseColor(hex)
                floatArrayOf(Color.red(c).toFloat(), Color.green(c).toFloat(), Color.blue(c).toFloat())
            }.toTypedArray()
            Palette(name, colors)
        }
    }
}
