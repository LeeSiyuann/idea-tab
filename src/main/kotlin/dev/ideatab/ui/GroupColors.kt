package dev.ideatab.ui

import com.intellij.ui.JBColor
import java.awt.Color

object GroupColors {
    val names = listOf("蓝色", "绿色", "紫色", "橙色", "粉色", "青色", "红色", "灰色")
    private val palette = listOf(
        0x3574F0 to 0x6C95EB, 0x288542 to 0x6BB77B, 0x8653C5 to 0xB28BE8, 0xB86D12 to 0xD8A054,
        0xB54889 to 0xD68ABD, 0x008C95 to 0x59BFC7, 0xC34141 to 0xE08080, 0x68747D to 0x9FAAB3
    )
    fun get(index: Int): Color {
        val (light, dark) = palette[Math.floorMod(index, palette.size)]
        return JBColor(Color(light), Color(dark))
    }
}
