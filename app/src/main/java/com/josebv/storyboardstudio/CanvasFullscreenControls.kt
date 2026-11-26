package com.josebv.storyboardstudio

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.widget.Button

/** Temporary fullscreen toggle. Its placeholder icons can be replaced by the final SVG assets. */
object CanvasFullscreenControls {
    fun createToggleButton(context: Context, onToggle: () -> Unit): Button = Button(context).apply {
        contentDescription = "Pantalla completa"
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minimumWidth = 0
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(83, 83, 83)))
        updateToggleIcon(context, this, false)
        setOnClickListener { onToggle() }
    }

    fun updateToggleIcon(context: Context, button: Button, fullscreen: Boolean) {
        val resource = if (fullscreen) R.drawable.ic_clear else R.drawable.ic_export_png
        val bitmap = BitmapFactory.decodeResource(context.resources, resource) ?: return
        val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
        val size = (30f * context.resources.displayMetrics.density + 0.5f).toInt()
        drawable.setBounds(0, 0, size, size)
        drawable.alpha = 255
        button.text = ""
        button.contentDescription = if (fullscreen) "Mostrar paneles" else "Pantalla completa"
        button.setCompoundDrawables(null, drawable, null, null)
        button.gravity = android.view.Gravity.CENTER
    }
}
