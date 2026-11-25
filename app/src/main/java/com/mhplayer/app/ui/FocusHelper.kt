package com.mhplayer.app.ui

import android.view.View

/**
 * DPAD focus helpers: every clickable item is focusable with a visible red
 * highlight, and the first item of a screen takes focus when it starts.
 */
object FocusHelper {

    /** Requests focus on [view] once it is laid out (no-op if detached). */
    fun requestFocus(view: View?) {
        view ?: return
        view.post { view.requestFocus() }
    }

    /** Makes a view DPAD-focusable with the shared red focus background. */
    fun makeFocusable(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setBackgroundResource(com.mhplayer.app.R.drawable.selector_focusable)
    }
}
