package com.jmedeisis.draglinearlayout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Holds state information about the currently dragged item.
 * <p/>
 * Rough lifecycle:
 * <li>#startDetectingOnPossibleDrag - #detecting == true</li>
 * <li>     if drag is recognised, #onDragStart - #dragging == true</li>
 * <li>     if drag ends, #onDragStop - #dragging == false, #settling == true</li>
 * <li>if gesture ends without drag, or settling finishes, #stopDetecting - #detecting == false</li>
 */
public class DragItem {

    private final long NOMINAL_SWITCH_DURATION = 150;
    private final long MIN_SWITCH_DURATION = NOMINAL_SWITCH_DURATION;
    private final long MAX_SWITCH_DURATION = NOMINAL_SWITCH_DURATION * 2;

    private View view;
    private int startVisibility;
    private BitmapDrawable viewDrawable;
    private int position;
    private int startHead;
    private int thickness;
    private int totalDragOffset;
    private int targetHeadOffset;
    private int orthogonalOffset;
    private ValueAnimator orthogonalDragStartAnimation;
    private ValueAnimator orthogonalDragSettleAnimation;
    private ValueAnimator settleAnimation;
    private final int mOrientation;

    private boolean detecting;
    private boolean dragging;
    private Drawable dragShadowDrawable;
    private int dragShadowHeight;
    private int orthogonalDragOffsetScaled;
    private float nominalDistanceScaled;

    public DragItem(int orientation) {
        this.mOrientation = orientation;
        stopDetecting();
    }

    public void startDetectingOnPossibleDrag(final View view, final int position) {
        this.view = view;
        this.startVisibility = view.getVisibility();
        updateViewDrawable();
        this.position = position;

        if (mOrientation == LinearLayout.VERTICAL) {
            this.startHead = view.getTop();
            this.thickness = view.getHeight();
        } else if (mOrientation == LinearLayout.HORIZONTAL) {
            this.startHead = view.getLeft();
            this.thickness = view.getWidth();
        }
        this.totalDragOffset = 0;
        this.targetHeadOffset = 0;
        this.settleAnimation = null;
        this.detecting = true;
    }

    public void updateViewDrawable() {
        viewDrawable = getDragDrawable(view);
    }

    public void onDragStart(final View container) {
        view.setVisibility(View.INVISIBLE);
        this.dragging = true;

        orthogonalDragStartAnimation = ValueAnimator.ofFloat(0, orthogonalDragOffsetScaled)
                .setDuration(NOMINAL_SWITCH_DURATION);
        orthogonalDragStartAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setOrthogonalOffset(((Float) animation.getAnimatedValue()).intValue());
                container.invalidate();
            }
        });

        orthogonalDragStartAnimation.start();


    }

    public void setTotalOffset(int offset) {
        totalDragOffset = offset;
        updateTargetHead();
    }

    public void updateTargetHead() {
        switch (mOrientation) {
            case LinearLayout.VERTICAL:
                targetHeadOffset = startHead - view.getTop() + totalDragOffset;
                break;
            case LinearLayout.HORIZONTAL:
                targetHeadOffset = startHead - view.getLeft() + totalDragOffset;
        }
    }

    /**
     * Animates the dragged item to its final resting position.
     */
    public void onDragStop(final View container) {
        // settle in drag direction
        settleAnimation = ValueAnimator.ofFloat(totalDragOffset, totalDragOffset - targetHeadOffset)
                .setDuration(getTranslateAnimationDuration(targetHeadOffset));
        settleAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (!detecting) return; // already stopped
                setTotalOffset(((Float) animation.getAnimatedValue()).intValue());

                final int shadowAlpha = (int) ((1 - animation.getAnimatedFraction()) * 255);
                if (null != dragShadowDrawable) dragShadowDrawable.setAlpha(shadowAlpha);
                container.invalidate();
            }
        });
        settleAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                dragging = false;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!detecting) {
                    return; // already stopped
                }

                settleAnimation = null;
                stopDetecting();

                if (null != dragShadowDrawable) dragShadowDrawable.setAlpha(255);
            }
        });
        settleAnimation.start();

        // settle orthogonal, using the same duration as drag direction settle duration
        orthogonalDragSettleAnimation = ValueAnimator.ofFloat(orthogonalOffset, 0).
                setDuration(getTranslateAnimationDuration(targetHeadOffset));
        orthogonalDragSettleAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setOrthogonalOffset(((Float) animation.getAnimatedValue()).intValue());
                container.invalidate();
            }
        });
        orthogonalDragSettleAnimation.start();


    }

    public boolean settling() {
        return null != settleAnimation;
    }

    public void stopDetecting() {
        this.detecting = false;
        if (null != view) view.setVisibility(startVisibility);
        view = null;
        startVisibility = -1;
        viewDrawable = null;
        position = -1;
        startHead = -1;
        thickness = -1;
        totalDragOffset = 0;
        targetHeadOffset = 0;
        if (null != settleAnimation) settleAnimation.end();
        settleAnimation = null;
    }

    /**
     * Draws the DragItem viewDrawable and shadows onto the canvas
     * @param canvas the canvas to draw to
     */
    public void draw(Canvas canvas) {
        if (detecting && (dragging || settling())) {
            canvas.save();
            switch (mOrientation) {
                case LinearLayout.VERTICAL:
                    canvas.translate(orthogonalOffset, totalDragOffset);
                    break;
                case LinearLayout.HORIZONTAL:
                    canvas.translate(totalDragOffset, orthogonalOffset);
                    break;
            }

            final int left = viewDrawable.getBounds().left;
            final int right = viewDrawable.getBounds().right;
            final int top = viewDrawable.getBounds().top;
            final int bottom = viewDrawable.getBounds().bottom;
            dragShadowDrawable.setBounds(left - dragShadowHeight, top - dragShadowHeight, right + dragShadowHeight, bottom + dragShadowHeight);
            dragShadowDrawable.draw(canvas);
            viewDrawable.draw(canvas);

            canvas.restore();
        }
    }

    private BitmapDrawable getDragDrawable(View view) {
        int top = view.getTop();
        int left = view.getLeft();

        Bitmap bitmap = getBitmapFromView(view);

        BitmapDrawable drawable = new BitmapDrawable(view.getResources(), bitmap);

        drawable.setBounds(new Rect(left, top, left + view.getWidth(), top + view.getHeight()));

        return drawable;
    }

    /**
     * @return a bitmap showing a screenshot of the view passed in.
     */
    private static Bitmap getBitmapFromView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    /**
     * A linear relationship b/w distance and duration, bounded.
     */
    private long getTranslateAnimationDuration(float distance) {
        return Math.min(MAX_SWITCH_DURATION, Math.max(MIN_SWITCH_DURATION,
                (long) (NOMINAL_SWITCH_DURATION * Math.abs(distance) / nominalDistanceScaled)));
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getTotalDragOffset() {
        return totalDragOffset;
    }

    public ValueAnimator getSettleAnimation() {
        return settleAnimation;
    }

    public void setDragShadowDrawable(Drawable dragShadowDrawable) {
        this.dragShadowDrawable = dragShadowDrawable;
    }

    public void setDragShadowHeight(int dragShadowHeight) {
        this.dragShadowHeight = dragShadowHeight;
    }

    public void setOrthogonalDragOffsetScaled(int orthogonalDragOffsetScaled) {
        this.orthogonalDragOffsetScaled = orthogonalDragOffsetScaled;
    }

    public void setNominalDistanceScaled(float nominalDistanceScaled) {
        this.nominalDistanceScaled = nominalDistanceScaled;
    }

    public void setOrthogonalOffset(int orthogonalOffset) {
        this.orthogonalOffset = orthogonalOffset;
    }

    public boolean isDetecting() {
        return detecting;
    }

    public int getThickness() {
        return thickness;
    }

    public int getStartHead() {
        return startHead;
    }

    public int getPosition() {
        return position;
    }

    public View getView() {
        return view;
    }

    public boolean isDragging() {
        return dragging;
    }
}
