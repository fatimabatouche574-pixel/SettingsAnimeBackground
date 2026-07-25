package com.local.settingsanimebackground.hook

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

class BackgroundLayer(context: Context) : FrameLayout(context) {
    val imageView = ImageView(context)
    val dimView = View(context)
    lateinit var controller: BackgroundController

    init {
        tag = LAYER_TAG
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        imageView.isClickable = false
        imageView.isFocusable = false
        imageView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        dimView.setBackgroundColor(Color.BLACK)
        dimView.isClickable = false
        dimView.isFocusable = false
        dimView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    companion object {
        const val LAYER_TAG = "com.local.settingsanimebackground.BACKGROUND_LAYER"
    }
}
