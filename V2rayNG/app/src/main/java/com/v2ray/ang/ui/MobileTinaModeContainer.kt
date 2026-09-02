package com.v2ray.ang.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Observes deliberate horizontal swipes without stealing touch events from the child
 * ScrollView/RecyclerView/ViewPager hierarchy.
 *
 * Direction: +1 = left-to-right (Manual), -1 = right-to-left (Auto).
 */
class MobileTinaModeContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val triggerThreshold = 58f * density
    private val horizontalBias = 1.2f

    private var downX = 0f
    private var downY = 0f
    private var trackingGesture = false
    private var swipeTriggered = false
    private var ownsUnclaimedTouchSequence = false
    private var swipeListener: ((direction: Int) -> Unit)? = null

    fun setOnModeSwipeListener(listener: (direction: Int) -> Unit) {
        swipeListener = listener
    }

    private fun isHorizontalSwipe(event: MotionEvent): Boolean {
        val dx = event.x - downX
        val dy = event.y - downY
        return trackingGesture &&
            !swipeTriggered &&
            abs(dx) >= triggerThreshold &&
            abs(dx) > abs(dy) * horizontalBias
    }

    private fun triggerSwipe(event: MotionEvent) {
        if (!isHorizontalSwipe(event)) return
        swipeTriggered = true
        val dx = event.x - downX
        val direction = if (dx > 0f) 1 else -1

        // Switch mode after the current dispatch pass. Empty ViewPager2 can cancel its child
        // sequence before ACTION_UP, so waiting until UP is not reliable on first run.
        post { swipeListener?.invoke(direction) }
    }

    /**
     * Observe the complete gesture at container level. dispatchTouchEvent() is called
     * before child views, so a horizontal swipe is detected even when the manual server
     * list, TabLayout, ScrollView or ViewPager handles/cancels the touch sequence itself.
     * Vertical scrolling and normal clicks remain entirely with the child view.
     *
     * When no subscription/config exists yet, an empty ScrollView/ViewPager may either
     * decline ACTION_DOWN or later cancel the sequence. The container therefore keeps any
     * otherwise-unhandled sequence alive and recognizes a deliberate horizontal swipe on
     * ACTION_MOVE as soon as it crosses the threshold, with ACTION_UP retained as fallback.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingGesture = true
                swipeTriggered = false

                val handled = super.dispatchTouchEvent(event)
                ownsUnclaimedTouchSequence = !handled
                return handled || ownsUnclaimedTouchSequence
            }

            MotionEvent.ACTION_MOVE -> {
                triggerSwipe(event)
                val handled = super.dispatchTouchEvent(event)
                return handled || ownsUnclaimedTouchSequence || swipeTriggered
            }

            MotionEvent.ACTION_UP -> {
                // Fallback for very short gestures where Android coalesces movement and the
                // threshold is reached only by the final UP coordinates.
                triggerSwipe(event)
                val handled = super.dispatchTouchEvent(event)
                val ownedByContainer = ownsUnclaimedTouchSequence
                val triggered = swipeTriggered
                trackingGesture = false
                swipeTriggered = false
                ownsUnclaimedTouchSequence = false
                return handled || ownedByContainer || triggered
            }

            MotionEvent.ACTION_CANCEL -> {
                val handled = super.dispatchTouchEvent(event)
                val ownedByContainer = ownsUnclaimedTouchSequence
                val triggered = swipeTriggered
                trackingGesture = false
                swipeTriggered = false
                ownsUnclaimedTouchSequence = false
                return handled || ownedByContainer || triggered
            }

            else -> {
                val handled = super.dispatchTouchEvent(event)
                return handled || ownsUnclaimedTouchSequence || swipeTriggered
            }
        }
    }
}
