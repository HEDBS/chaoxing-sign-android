package com.example.chaoxingsign;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 九宫格手势画板(3x3): 触摸连线, 生成手势编码
 *
 * 编码规则(已逆向): 九宫格编号 1-9(左上=1, 横向排列), 编码 = 触摸经过的点编号序列
 *  1 2 3
 *  4 5 6
 *  7 8 9
 * 例: Z字形(左上→右下) = 1235789
 *
 * 触摸滑动经过的点(进入点圆形范围)按顺序记录, 抬起时通过回调返回编码
 */
public class GestureView extends View {

    /** 手势完成回调: 返回点编号序列(编码) */
    public interface OnGestureListener {
        void onGestureDone(String code);
    }

    private static final int N = 3; // 3x3
    private final List<int[]> points = new ArrayList<>(); // 9 个点坐标 [x,y]
    private final List<Integer> path = new ArrayList<>(); // 经过的点编号(1-9)
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float curX, curY; // 当前手指位置(画线用)
    private boolean dragging = false;
    private OnGestureListener listener;

    public GestureView(Context context) {
        this(context, null);
    }

    public GestureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dotPaint.setColor(0xFFBDBDBD);          // 未选点灰色
        dotPaint.setStyle(Paint.Style.FILL);
        dotSelectedPaint.setColor(0xFFFF7043);   // 已选点橙色
        dotSelectedPaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(0xFFFF7043);
        linePaint.setStrokeWidth(8f);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    public void setOnGestureListener(OnGestureListener l) {
        this.listener = l;
    }

    /** 清除已画手势 */
    public void clear() {
        path.clear();
        dragging = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        points.clear();
        int size = Math.min(w, h);
        int margin = size / 6;
        int step = (size - 2 * margin) / (N - 1);
        for (int row = 0; row < N; row++) {
            for (int col = 0; col < N; col++) {
                points.add(new int[]{margin + col * step, margin + row * step});
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 画已连接线段
        if (path.size() > 1) {
            for (int i = 0; i < path.size() - 1; i++) {
                int[] p1 = points.get(path.get(i) - 1);
                int[] p2 = points.get(path.get(i + 1) - 1);
                canvas.drawLine(p1[0], p1[1], p2[0], p2[1], linePaint);
            }
        }
        // 画手指当前连线(未完成段)
        if (dragging && !path.isEmpty()) {
            int[] last = points.get(path.get(path.size() - 1) - 1);
            canvas.drawLine(last[0], last[1], curX, curY, linePaint);
        }
        // 画点
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            boolean selected = path.contains(i + 1);
            canvas.drawCircle(p[0], p[1], 30f, selected ? dotSelectedPaint : dotPaint);
        }
    }

    /** 根据触摸坐标判断命中的点编号(1-9), 未命中返回 0 */
    private int hitPoint(float x, float y) {
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            float dx = x - p[0], dy = y - p[1];
            if (dx * dx + dy * dy <= 45f * 45f) return i + 1;
        }
        return 0;
    }

    /** 两点线段穿过的中间点(如 1->9 穿过 5, 3->7 穿过 5), 无则返回 0 */
    private int middlePoint(int a, int b) {
        int[] pa = points.get(a - 1), pb = points.get(b - 1);
        for (int i = 0; i < points.size(); i++) {
            int m = i + 1;
            if (m == a || m == b) continue;
            if (isBetween(pa, pb, points.get(i))) return m;
        }
        return 0;
    }

    /** 点 m 是否在线段 a-b 上(共线且位于两点之间) */
    private boolean isBetween(int[] a, int[] b, int[] m) {
        int cross = (b[0] - a[0]) * (m[1] - a[1]) - (b[1] - a[1]) * (m[0] - a[0]);
        if (cross != 0) return false; // 不共线
        int dot = (m[0] - a[0]) * (m[0] - b[0]) + (m[1] - a[1]) * (m[1] - b[1]);
        return dot < 0; // 位于 a、b 之间
    }

    /** 记录新点: 若与上一点连线穿过未选中间点, 自动先加入中间点 */
    private void addPoint(int p) {
        if (path.contains(p)) return;
        if (!path.isEmpty()) {
            int last = path.get(path.size() - 1);
            int mid = middlePoint(last, p);
            if (mid != 0 && !path.contains(mid)) path.add(mid); // 自动包含(如1->9选5)
        }
        path.add(p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                path.clear();
                curX = x;
                curY = y;
                int p = hitPoint(x, y);
                if (p != 0) addPoint(p);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                curX = x;
                curY = y;
                int q = hitPoint(x, y);
                if (q != 0) addPoint(q); // 滑过新点(含自动包含中间点)则加入路径
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                invalidate();
                if (listener != null && !path.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int id : path) sb.append(id);
                    listener.onGestureDone(sb.toString());
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
