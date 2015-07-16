package com.jmedeisis.draglinearlayout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MotionEventCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.LinearLayout;

/**
 * A LinearLayout that supports children Views that can be dragged and swapped around.
 * See {@link #addDragView(android.view.View, android.view.View)},
 * {@link #addDragView(android.view.View, android.view.View, int)},
 * {@link #setViewDraggable(android.view.View, android.view.View)}, and
 * {@link #removeDragView(android.view.View)}.
 * <p/>
 * Currently, no error-checking is done on standard {@link #addView(android.view.View)} and
 * {@link #removeView(android.view.View)} calls, so avoid using these with children previously
 * declared as draggable to prevent memory leaks and/or subtle bugs. Pull requests welcome!
 */
public class DragLinearLayout extends LinearLayout {
    private static final String LOG_TAG = DragLinearLayout.class.getSimpleName();
    private static final long NOMINAL_SWITCH_DURATION = 150;
    private static final long MIN_SWITCH_DURATION = NOMINAL_SWITCH_DURATION;
    private static final long MAX_SWITCH_DURATION = NOMINAL_SWITCH_DURATION * 2;
    private static final float NOMINAL_DISTANCE = 20;
    private static final int DEFAULT_ORTHOGONAL_DRAG_OFFSET = 0;
    public static final int LONG_CLICK_MIN_DURATION = 200;
    private final float nominalDistanceScaled;


    private int orthogonalDragOffsetScaled;

    /**
     * Use with {@link com.jmedeisis.draglinearlayout.DragLinearLayout#setOnViewSwapListener(com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener)}
     * to listen for draggable view swaps.
     */
    public interface OnViewSwapListener {
        /**
         * Invoked right before the two items are swapped due to a drag event.
         * After the swap, the firstView will be in the secondPosition, and vice versa.
         * <p/>
         * No guarantee is made as to which of the two has a lesser/greater position.
         */
        void onSwap(View firstView, int firstPosition, View secondView, int secondPosition);
    }

    private OnViewSwapListener swapListener;

    /**
     * Use with {@link com.jmedeisis.draglinearlayout.DragLinearLayout#setOnViewDragListener(com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewDragListener)}
     * to listen for drag events.
     */
    public interface OnViewDragListener {
        /**
         * Invoked right before the view is animated to its dragging state
         */
        void onStartDrag(View view);

        /**
         * Invoked right before the view is animated to its regular state
         */
        void onStopDrag(View view);
    }

    private OnViewDragListener dragListener;

    private LayoutTransition layoutTransition;

    /**
     * Mapping from child index to drag-related info container.
     * Presence of mapping implies the child can be dragged, and is considered for swaps with the
     * currently dragged item.
     */
    private final SparseArray<DraggableChild> draggableChildren;

    private class DraggableChild {
        /**
         * If non-null, a reference to an on-going position animation.
         */
        private ValueAnimator swapAnimation;

        public void endExistingAnimation() {
            if (null != swapAnimation) swapAnimation.end();
        }

        public void cancelExistingAnimation() {
            if (null != swapAnimation) swapAnimation.cancel();
        }
    }

    /**
     * The currently dragged item, if {@link com.jmedeisis.draglinearlayout.DragItem#detecting}.
     */
    private final DragItem draggedItem;
    private final int slop;

    private static final int INVALID_POINTER_ID = -1;
    private int downPos = -1;
    private int activePointerId = INVALID_POINTER_ID;

    /**
     * The shadow to be drawn around the {@link #draggedItem}.
     */
    private final Drawable dragShadowDrawable;
    private final int dragShadowHeight;

    /**
     * See {@link #setContainerScrollView(View)}.
     */
    private View containerScrollView;
    private int scrollSensitiveAreaThickness;
    private static final int DEFAULT_SCROLL_SENSITIVE_AREA_HEIGHT_DP = 48;
    private static final int DEFAULT_SCROLL_SENSITIVE_AREA_WIDTH_DP = 48;
    private static final int MAX_DRAG_SCROLL_SPEED = 16;

    public DragLinearLayout(Context context) {
        this(context, null);
    }

    public DragLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        draggableChildren = new SparseArray<>();

        final Resources resources = getResources();
        dragShadowDrawable = ContextCompat.getDrawable(context, R.drawable.shadow_light);
        dragShadowHeight = resources.getDimensionPixelSize(R.dimen.downwards_drop_shadow_height);
        nominalDistanceScaled = (int) (NOMINAL_DISTANCE * resources.getDisplayMetrics().density + 0.5f);
        orthogonalDragOffsetScaled = (int) (DEFAULT_ORTHOGONAL_DRAG_OFFSET * resources.getDisplayMetrics().density + 0.5f);

        draggedItem = new DragItem(getOrientation());
        draggedItem.setDragShadowDrawable(dragShadowDrawable);
        draggedItem.setDragShadowHeight(dragShadowHeight);
        draggedItem.setOrthogonalDragOffsetScaled(orthogonalDragOffsetScaled);
        draggedItem.setNominalDistanceScaled(nominalDistanceScaled);
        ViewConfiguration vc = ViewConfiguration.get(context);
        slop = vc.getScaledTouchSlop();

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DragLinearLayout, 0, 0);
        try {
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    scrollSensitiveAreaThickness = a.getDimensionPixelSize(R.styleable.DragLinearLayout_scrollSensitiveHeight,
                            (int) (DEFAULT_SCROLL_SENSITIVE_AREA_HEIGHT_DP * resources.getDisplayMetrics().density + 0.5f));
                    break;
                case LinearLayout.HORIZONTAL:
                    scrollSensitiveAreaThickness = a.getDimensionPixelSize(R.styleable.DragLinearLayout_scrollSensitiveWidth,
                            (int) (DEFAULT_SCROLL_SENSITIVE_AREA_WIDTH_DP * resources.getDisplayMetrics().density + 0.5f));
                    break;
            }
        } finally {
            a.recycle();
        }
    }

    /**
     * Calls {@link #addView(android.view.View)} followed by {@link #setViewDraggable(android.view.View, android.view.View)}.
     */
    public void addDragView(View child, View dragHandle) {
        addView(child);
        setViewDraggable(child, dragHandle);
    }

    /**
     * Calls {@link #addView(android.view.View, int)} followed by
     * {@link #setViewDraggable(android.view.View, android.view.View)} and correctly updates the
     * drag-ability state of all existing views.
     */
    public void addDragView(View child, View dragHandle, int index) {
        addView(child, index);

        // update drag-able children mappings
        final int numMappings = draggableChildren.size();
        for (int i = numMappings - 1; i >= 0; i--) {
            final int key = draggableChildren.keyAt(i);
            if (key >= index) {
                draggableChildren.put(key + 1, draggableChildren.get(key));
            }
        }

        setViewDraggable(child, dragHandle);
    }

    /**
     * Makes the child a candidate for dragging. Must be an existing child of this layout.
     */
    public void setViewDraggable(View child, View dragHandle) {
        if (null == child || null == dragHandle) {
            throw new IllegalArgumentException(
                "Draggable children and their drag handles must not be null.");
        }
        
        if (this == child.getParent()) {
            dragHandle.setOnTouchListener(new DragHandleOnTouchListener(child));
            draggableChildren.put(indexOfChild(child), new DraggableChild());
        } else {
            Log.e(LOG_TAG, child + " is not a child, cannot make draggable.");
        }
    }

    /**
     * Calls {@link #removeView(android.view.View)} and correctly updates the drag-ability state of
     * all remaining views.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void removeDragView(View child) {
        if (this == child.getParent()) {
            final int index = indexOfChild(child);
            removeView(child);

            // update drag-able children mappings
            final int mappings = draggableChildren.size();
            for (int i = 0; i < mappings; i++) {
                final int key = draggableChildren.keyAt(i);
                if (key >= index) {
                    DraggableChild next = draggableChildren.get(key + 1);
                    if (null == next) {
                        draggableChildren.delete(key);
                    } else {
                        draggableChildren.put(key, next);
                    }
                }
            }
        }
    }

    @Override
    public void removeAllViews() {
        super.removeAllViews();
        draggableChildren.clear();
    }

    /**
     * If this layout is within a {@link android.widget.ScrollView}, register it here so that it
     * can be scrolled during item drags.
     */
    public void setContainerScrollView(View scrollView) {
        this.containerScrollView = scrollView;
    }

    /**
     * Sets the height from upper / lower edge at which a container {@link android.widget.ScrollView},
     * if one is registered via {@link #setContainerScrollView(View)},
     * is scrolled.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setScrollSensitiveHeight(int height) {
        this.scrollSensitiveAreaThickness = height;
    }

    @SuppressWarnings("UnusedDeclaration")
    public int getScrollSensitiveHeight() {
        return scrollSensitiveAreaThickness;
    }

    /**
     * Sets the width from right / left edge at which a container {@link android.widget.ScrollView},
     * if one is registered via {@link #setContainerScrollView(View)},
     * is scrolled.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setScrollSensitiveWidth(int width) {
        this.scrollSensitiveAreaThickness = width;
    }

    @SuppressWarnings("UnusedDeclaration")
    public int getScrollSensitiveWidth() {
        return scrollSensitiveAreaThickness;
    }

    /**
     * See {@link com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener}.
     */
    public void setOnViewSwapListener(OnViewSwapListener swapListener) {
        this.swapListener = swapListener;
    }

    /**
     * See {@link com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener}.
     */
    public void setOnViewDragListener(OnViewDragListener dragListener) {
        this.dragListener = dragListener;
    }

    /**
     * Sets the orthogonal offset that a view will be moved while being dragged
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setOrthogonalDragOffset(int orthogonalDragOffset) {
        this.orthogonalDragOffsetScaled = (int) (orthogonalDragOffset * getResources().getDisplayMetrics().density + 0.5f);
        draggedItem.setOrthogonalDragOffsetScaled(orthogonalDragOffsetScaled);
    }

    /**
     * A linear relationship b/w distance and duration, bounded.
     */
    private long getTranslateAnimationDuration(float distance) {
        return Math.min(MAX_SWITCH_DURATION, Math.max(MIN_SWITCH_DURATION,
                (long) (NOMINAL_SWITCH_DURATION * Math.abs(distance) / nominalDistanceScaled)));
    }

    /**
     * Initiates a new {@link #draggedItem} unless the current one is still
     * {@link com.jmedeisis.draglinearlayout.DragItem#detecting}.
     */
    private void startDetectingDrag(View child) {
        if (draggedItem.isDetecting())
            return; // existing drag in process, only one at a time is allowed

        final int position = indexOfChild(child);

        // complete any existing animations, both for the newly selected child and the previous dragged one
        if (draggableChildren.get(position) instanceof DraggableChild) {
            draggableChildren.get(position).endExistingAnimation();
        }

        draggedItem.startDetectingOnPossibleDrag(child, position);
    }

    private void startDrag() {
        // remove layout transition, it conflicts with drag animation
        // we will restore it after drag animation end, see onDragStop()
        layoutTransition = getLayoutTransition();
        if (layoutTransition != null) {
            setLayoutTransition(null);
        }

        if (dragListener != null) {
            dragListener.onStartDrag(draggedItem.getView());
            draggedItem.updateViewDrawable();
        }
        draggedItem.onDragStart(DragLinearLayout.this);
        requestDisallowInterceptTouchEvent(true);
    }

    /**
     * Updates the dragged item with the given total offset from its starting position.
     * Evaluates and executes draggable view swaps.
     */
    private void onDrag(final int offset) {
        draggedItem.setTotalOffset(offset);
        invalidate();

        int currentHead = draggedItem.getStartHead() + draggedItem.getTotalDragOffset();

        handleContainerScroll(currentHead);

        int belowPosition = nextDraggablePosition(draggedItem.getPosition());
        int abovePosition = previousDraggablePosition(draggedItem.getPosition());

        View belowView = getChildAt(belowPosition);
        View aboveView = getChildAt(abovePosition);

        // TODO(cmcneil): Figure out more reasonable defaults.
        int belowViewHead = 0;
        int belowViewThickness = 0;
        int aboveViewHead = 0;
        int aboveViewThickness = 0;
        switch (getOrientation()) {
            case LinearLayout.VERTICAL:
                if (belowView != null) {
                    belowViewHead = belowView.getTop();
                    belowViewThickness = belowView.getHeight();
                }
                if (aboveView != null) {
                    aboveViewHead = aboveView.getTop();
                    aboveViewThickness = aboveView.getHeight();
                }
                break;
            case LinearLayout.HORIZONTAL:
                if (belowView != null) {
                    belowViewHead = belowView.getLeft();
                    belowViewThickness = belowView.getWidth();
                }
                if (aboveView != null) {
                    aboveViewHead = aboveView.getLeft();
                    aboveViewThickness = aboveView.getWidth();
                }
                break;
            default:
                // TODO(cmcneil): Flip out.
        }

        final boolean isBelow = (belowView != null) &&
                (currentHead + draggedItem.getThickness() > belowViewHead + belowViewThickness / 2);
        final boolean isAbove = (aboveView != null) &&
                (currentHead < aboveViewHead + aboveViewThickness / 2);

        if (isBelow || isAbove) {
            final View switchView = isBelow ? belowView : aboveView;

            // swap elements
            final int originalPosition = draggedItem.getPosition();
            final int switchPosition = isBelow ? belowPosition : abovePosition;

            draggableChildren.get(switchPosition).cancelExistingAnimation();
            // TODO(cmcneil): Determine more reasonable default. Pos means something different.
            //  use pix or something instead.
            float startPos = 0;
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    startPos = switchView.getY();
                    break;
                case LinearLayout.HORIZONTAL:
                    startPos = switchView.getX();
                    break;
            }
            final float switchViewStartPos = startPos;

            if (null != swapListener) {
                swapListener.onSwap(draggedItem.getView(), draggedItem.getPosition(), switchView, switchPosition);
            }

            if (isBelow) {
                removeViewAt(originalPosition);
                removeViewAt(switchPosition - 1);

                addView(belowView, originalPosition);
                addView(draggedItem.getView(), switchPosition);
            } else {
                removeViewAt(switchPosition);
                removeViewAt(originalPosition - 1);

                addView(draggedItem.getView(), switchPosition);
                addView(aboveView, originalPosition);
            }
            draggedItem.setPosition(switchPosition);

            final ViewTreeObserver switchViewObserver = switchView.getViewTreeObserver();
            switchViewObserver.addOnPreDrawListener(new OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    switchViewObserver.removeOnPreDrawListener(this);

                    float currentPos = switchViewStartPos;
                    String dimension = "y";
                    switch (getOrientation()) {
                        case LinearLayout.VERTICAL:
                            currentPos = switchView.getTop();
                            dimension = "y";
                            break;
                        case LinearLayout.HORIZONTAL:
                            currentPos = switchView.getLeft();
                            dimension = "x";
                            break;
                    }
                    final ObjectAnimator switchAnimator = ObjectAnimator.ofFloat(switchView,
                            dimension, switchViewStartPos, currentPos)
                            .setDuration(getTranslateAnimationDuration(currentPos - switchViewStartPos));
                    switchAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            draggableChildren.get(originalPosition).swapAnimation = switchAnimator;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            draggableChildren.get(originalPosition).swapAnimation = null;
                        }
                    });
                    switchAnimator.start();

                    return true;
                }
            });

            final ViewTreeObserver observer = draggedItem.getView().getViewTreeObserver();
            observer.addOnPreDrawListener(new OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    observer.removeOnPreDrawListener(this);
                    draggedItem.updateTargetHead();

                    // TODO test if still necessary..
                    // because draggedItem#view#getTop() is only up-to-date NOW
                    // (and not right after the #addView() swaps above)
                    // we may need to update an ongoing settle animation
                    if (draggedItem.settling()) {
                        Log.d(LOG_TAG, "Updating settle animation");
                        draggedItem.getSettleAnimation().removeAllListeners();
                        draggedItem.getSettleAnimation().cancel();
                        stopDrag();
                    }
                    return true;
                }
            });
        }
    }

    public void stopDrag() {
        // restore layout transition
        if (layoutTransition != null && getLayoutTransition() == null) {
            setLayoutTransition(layoutTransition);
        }
        if (dragListener != null) {
            dragListener.onStopDrag(draggedItem.getView());
        }
        draggedItem.onDragStop(DragLinearLayout.this);
    }

    private int previousDraggablePosition(int position) {
        int startIndex = draggableChildren.indexOfKey(position);
        if (startIndex < 1 || startIndex > draggableChildren.size()) return -1;
        return draggableChildren.keyAt(startIndex - 1);
    }

    private int nextDraggablePosition(int position) {
        int startIndex = draggableChildren.indexOfKey(position);
        if (startIndex < -1 || startIndex > draggableChildren.size() - 2) return -1;
        return draggableChildren.keyAt(startIndex + 1);
    }

    private Runnable dragUpdater;

    // TODO(cmcneil): Generalize callers.
    private void handleContainerScroll(final int currentHead) {
        if (null != containerScrollView) {
            final int startScrollX = containerScrollView.getScrollX();
            final int startScrollY = containerScrollView.getScrollY();
            final int absHead;
            final int thickness;
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    absHead = getTop() - startScrollY + currentHead;
                    thickness = containerScrollView.getHeight();
                    break;
                case LinearLayout.HORIZONTAL:
                    absHead = getLeft() - startScrollX + currentHead;
                    thickness = containerScrollView.getWidth();
                    break;
                default:
                    // TODO(cmcneil): Throw an error or something
                    absHead = 0;
                    thickness = 0;
            }

            final int delta;

            if (absHead < scrollSensitiveAreaThickness) {
                delta = (int) (-MAX_DRAG_SCROLL_SPEED * smootherStep(scrollSensitiveAreaThickness, 0, absHead));
            } else if (absHead > thickness - scrollSensitiveAreaThickness) {
                delta = (int) (MAX_DRAG_SCROLL_SPEED * smootherStep(thickness - scrollSensitiveAreaThickness, thickness, absHead));
            } else {
                delta = 0;
            }

            containerScrollView.removeCallbacks(dragUpdater);
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    containerScrollView.scrollBy(0, delta);
                    dragUpdater = new Runnable() {
                        @Override
                        public void run() {
                            if (draggedItem.isDragging() && startScrollY != containerScrollView.getScrollY()) {
                                onDrag(draggedItem.getTotalDragOffset() + delta);
                            }
                        }
                    };
                    break;
                case LinearLayout.HORIZONTAL:
                    containerScrollView.scrollBy(delta, 0);
                    dragUpdater = new Runnable() {
                        @Override
                        public void run() {
                            if (draggedItem.isDragging() && startScrollX != containerScrollView.getScrollX()) {
                                onDrag(draggedItem.getTotalDragOffset() + delta);
                            }
                        }
                    };
                    break;
            }
            containerScrollView.post(dragUpdater);
        }
    }

    /**
     * By Ken Perlin. See <a href="http://en.wikipedia.org/wiki/Smoothstep">Smoothstep - Wikipedia</a>.
     */
    private static float smootherStep(float edge1, float edge2, float val) {
        val = Math.max(0, Math.min((val - edge1) / (edge2 - edge1), 1));
        return val * val * val * (val * (val * 6 - 15) + 10);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        draggedItem.draw(canvas);
    }

    /*
     * Note regarding touch handling:
     * In general, we have three cases -
     * 1) User taps outside any children.
     *      #onInterceptTouchEvent receives DOWN
     *      #onTouchEvent receives DOWN
     *          draggedItem.detecting == false, we return false and no further events are received
     * 2) User taps on non-interactive drag handle / child, e.g. TextView or ImageView.
     *      #onInterceptTouchEvent receives DOWN
     *      DragHandleOnTouchListener (attached to each draggable child) #onTouch receives DOWN
     *      #startDetectingDrag is called, draggedItem is now detecting
     *      view does not handle touch, so our #onTouchEvent receives DOWN
     *          draggedItem.detecting == true, we #startDrag() and proceed to handle the drag
     * 3) User taps on interactive drag handle / child, e.g. Button.
     *      #onInterceptTouchEvent receives DOWN
     *      DragHandleOnTouchListener (attached to each draggable child) #onTouch receives DOWN
     *      #startDetectingDrag is called, draggedItem is now detecting
     *      view handles touch, so our #onTouchEvent is not called yet
     *      #onInterceptTouchEvent receives ACTION_MOVE
     *      if dy > touch slop, we assume user wants to drag and intercept the event
     *      #onTouchEvent receives further ACTION_MOVE events, proceed to handle the drag
     *
     * For cases 2) and 3), lifting the active pointer at any point in the sequence of events
     * triggers #onTouchEnd and the draggedItem, if detecting, is #stopDetecting.
     */

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        Log.d(LOG_TAG, "onInterceptTouchEvent: " + event.getAction());
        switch (MotionEventCompat.getActionMasked(event)) {
            case MotionEvent.ACTION_DOWN: {
                if (draggedItem.isDetecting()) return false; // an existing item is (likely) settling
                switch (getOrientation()) {
                    case LinearLayout.VERTICAL:
                        downPos = (int) MotionEventCompat.getY(event, 0);
                        break;
                    case LinearLayout.HORIZONTAL:
                        downPos = (int) MotionEventCompat.getX(event, 0);
                }
                activePointerId = MotionEventCompat.getPointerId(event, 0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!draggedItem.isDetecting()) return false;
                if (INVALID_POINTER_ID == activePointerId) break;
                final int pointerIndex = event.findPointerIndex(activePointerId);
                boolean move = false;
                switch (getOrientation()) {
                    case LinearLayout.VERTICAL:
                        final float y = MotionEventCompat.getY(event, pointerIndex);
                        final float dy = y - downPos;
                        move = Math.abs(dy) > slop;
                        break;
                    case LinearLayout.HORIZONTAL:
                        final float x = MotionEventCompat.getX(event, pointerIndex);
                        final float dx = x - downPos;
                        move = Math.abs(dx) > slop;
                }

                if (!move) {
                    Log.d(LOG_TAG, "posting startdrag task from move");
                    handler.postDelayed(startDragOnLongPressRunnable, LONG_CLICK_MIN_DURATION);
                    potentialClick = true;
                } else {
                    Log.d(LOG_TAG, "removing callback from move");
                    handler.removeCallbacks(startDragOnLongPressRunnable);
                    potentialClick = false;
                }

                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = MotionEventCompat.getActionIndex(event);
                final int pointerId = MotionEventCompat.getPointerId(event, pointerIndex);

                if (pointerId != activePointerId)
                    break; // if active pointer, fall through and cancel!
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                onTouchEnd();

                if (draggedItem.isDetecting()) draggedItem.stopDetecting();
                break;
            }
        }

        this.onTouchEvent(event);
        return false;
    }

    final Handler handler = new Handler();
    private boolean potentialClick;
    final Runnable startDragOnLongPressRunnable = new Runnable() {
        public void run() {
            handler.removeCallbacks(startDragOnLongPressRunnable);
            potentialClick = false;
            startDrag();

            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(100);
        }
    };

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        Log.d(LOG_TAG, "onTouchEvent: " + event.getAction());
        switch (MotionEventCompat.getActionMasked(event)) {
            case MotionEvent.ACTION_DOWN: {
                if (!draggedItem.isDetecting() || draggedItem.settling()) return false;
                Log.d(LOG_TAG, "posting startdrag task");
                handler.postDelayed(startDragOnLongPressRunnable, LONG_CLICK_MIN_DURATION);
                potentialClick = true;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!draggedItem.isDragging()) break;
                if (INVALID_POINTER_ID == activePointerId) break;

                int pointerIndex = event.findPointerIndex(activePointerId);
                boolean move = false;
                int lastEventPos = downPos;

                switch (getOrientation()) {
                    case LinearLayout.VERTICAL:
                        final float y = MotionEventCompat.getY(event, pointerIndex);
                        final float dy = y - downPos;
                        move = Math.abs(dy) > slop;
                        lastEventPos = (int) MotionEventCompat.getY(event, pointerIndex);
                        break;
                    case LinearLayout.HORIZONTAL:
                        final float x = MotionEventCompat.getX(event, pointerIndex);
                        final float dx = x - downPos;
                        move = Math.abs(dx) > slop;
                        lastEventPos = (int) MotionEventCompat.getX(event, pointerIndex);
                        break;
                }

                if (move) {
                    Log.d(LOG_TAG, "removing callback");
                    handler.removeCallbacks(startDragOnLongPressRunnable);
                    potentialClick = false;
                }
                final int delta = lastEventPos - downPos;
                onDrag(delta);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = MotionEventCompat.getActionIndex(event);
                final int pointerId = MotionEventCompat.getPointerId(event, pointerIndex);

                if (pointerId != activePointerId)
                    break; // if active pointer, fall through and cancel!
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {

                if (potentialClick && MotionEventCompat.getActionMasked(event) == MotionEvent.ACTION_UP) {
                    potentialClick = false;
                    if (draggedItem.getView() != null /*&& TODO draggedItem.getView() implements Clickable */) {
                        draggedItem.getView().performClick();
                    }
                }
                Log.d(LOG_TAG, "removing callback");
                handler.removeCallbacks(startDragOnLongPressRunnable);
                onTouchEnd();

                if (draggedItem.isDragging()) {
                    stopDrag();
                } else if (draggedItem.isDetecting()) {
                    draggedItem.stopDetecting();
                }
                return true;
            }
        }
        return false;
    }

    private void onTouchEnd() {
        downPos = -1;
        activePointerId = INVALID_POINTER_ID;
    }

    private class DragHandleOnTouchListener implements OnTouchListener {
        private final View view;

        public DragHandleOnTouchListener(final View view) {
            this.view = view;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (MotionEvent.ACTION_DOWN == MotionEventCompat.getActionMasked(event)) {
                Log.d(LOG_TAG, "startDetectingDrag in DragHandleListener");
                startDetectingDrag(view);
            }
            return false;
        }
    }
}
