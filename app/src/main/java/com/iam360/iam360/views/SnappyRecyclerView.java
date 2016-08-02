package com.iam360.iam360.views;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.iam360.iam360.util.MixpanelHelper;
import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-01-07
 */

// source: http://stackoverflow.com/a/26445064/1176596
public final class SnappyRecyclerView extends RecyclerView {
    private boolean isScrollingEnabled = true;

    public SnappyRecyclerView(Context context) {
        super(context);
    }

    public SnappyRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SnappyRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        final LayoutManager lm = getLayoutManager();

        if (lm instanceof ISnappyLayoutManager) {
            super.smoothScrollToPosition(((ISnappyLayoutManager) getLayoutManager())
                    .getPositionForVelocity(velocityX, velocityY));

            MixpanelHelper.trackViewViewer2D(getContext());

            return true;
        }
        return super.fling(velocityX, velocityY);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // intercept only if scrolling is enabled
        if (isScrollingEnabled) {
            return super.onInterceptTouchEvent(event);
        } else {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Timber.v("received touch event in SnappyRecyclerView");
        if (isScrollingEnabled) {
            try {
                // We want the parent to handle all touch events--there's a lot going on there,
                // and there is no reason to overwrite that functionality--bad things will happen.
                final boolean ret = super.onTouchEvent(e);
                final LayoutManager lm = getLayoutManager();

                if (lm instanceof ISnappyLayoutManager
                        && (e.getAction() == MotionEvent.ACTION_UP ||
                        e.getAction() == MotionEvent.ACTION_CANCEL)
                        && getScrollState() == SCROLL_STATE_IDLE) {
                    // The layout manager is a SnappyLayoutManager, which means that the
                    // children should be snapped to a grid at the end of a drag or
                    // fling. The motion event is either a user lifting their finger or
                    // the cancellation of a motion events, so this is the time to take
                    // over the scrolling to perform our own functionality.
                    // Finally, the scroll state is idle--meaning that the resultant
                    // velocity after the user's gesture was below the threshold, and
                    // no fling was performed, so the view may be in an unaligned state
                    // and will not be flung to a proper state.

                    smoothScrollToPosition(((ISnappyLayoutManager) lm).getFixScrollPos());
                }

                return ret;
            } catch (Exception ex) {
                return false;
            }

        } else {
            // disable scrolling but still pipe touch events
            Timber.v("scrolling disabled but got touch event");
            return false;
        }
    }

    public boolean isScrollingEnabled() {
        return isScrollingEnabled;
    }

    public void toggleScrolling() {
        isScrollingEnabled = !isScrollingEnabled;
    }

    public void enableScrolling() {
        isScrollingEnabled = true;
    }

    public void disableScrolling() {
        isScrollingEnabled = false;
    }
}
