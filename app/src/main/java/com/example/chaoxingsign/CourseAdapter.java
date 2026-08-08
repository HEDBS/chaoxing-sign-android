package com.example.chaoxingsign;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 课程列表适配器: 把 List<Course> 渲染成 RecyclerView 的每一项
 *
 * RecyclerView 三大件: 数据 + Adapter + LayoutManager
 *  - Adapter: 负责"每一项长什么样" (onCreateViewHolder) 和"数据怎么填" (onBindViewHolder)
 *  - 关键机制: ViewHolder 复用 —— 滚动时不会无限创建新 View
 */
public class CourseAdapter extends RecyclerView.Adapter<CourseAdapter.CourseHolder> {

    /** 点击回调接口: MainActivity 实现, 收到点击后跳转签到页 */
    public interface OnCourseClickListener {
        void onCourseClick(ChaoxingApi.Course course);
    }

    private final List<ChaoxingApi.Course> courses;
    private OnCourseClickListener listener;

    public CourseAdapter(List<ChaoxingApi.Course> courses) {
        this.courses = courses;
    }

    public void setOnCourseClickListener(OnCourseClickListener l) {
        this.listener = l;
    }

    /** 刷新数据(活动状态变化后: 高亮/置顶复原) */
    public void update(List<ChaoxingApi.Course> newList) {
        courses.clear();
        courses.addAll(newList);
        notifyDataSetChanged();
    }

    public List<ChaoxingApi.Course> getCourses() {
        return courses;
    }

    /** 创建一项的 View 容器 (滚动时按需创建, 会被复用) */
    @NonNull
    @Override
    public CourseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_course, parent, false);
        return new CourseHolder(v);
    }

    /** 把数据填进 ViewHolder (每次滚动到可见时调用) */
    @Override
    public void onBindViewHolder(@NonNull CourseHolder holder, int position) {
        ChaoxingApi.Course c = courses.get(position);
        holder.tvName.setText(c.courseName);
        holder.tvClass.setText(c.className);
        // 徽标三态: 已签(灰"已签到") > 有活动未签(橙"有签到"+高亮) > 无活动(无)
        if (c.signed) {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            holder.tvBadge.setText("已签到");
            holder.tvBadge.setBackgroundResource(R.drawable.badge_bg_gray);
            holder.tvBadge.setVisibility(View.VISIBLE);
        } else if (c.hasActivity) {
            holder.itemView.setBackgroundColor(0x1AFF7043); // 浅橙背景
            holder.tvBadge.setText("有签到");
            holder.tvBadge.setBackgroundResource(R.drawable.badge_bg);
            holder.tvBadge.setVisibility(View.VISIBLE);
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            holder.tvBadge.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCourseClick(courses.get(position));
        });
    }

    @Override
    public int getItemCount() {
        return courses.size();
    }

    /** 一项的缓存容器: 持有这一项里的所有控件引用 */
    static class CourseHolder extends RecyclerView.ViewHolder {
        final TextView tvName, tvClass, tvBadge;

        CourseHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvCourseName);
            tvClass = itemView.findViewById(R.id.tvClassName);
            tvBadge = itemView.findViewById(R.id.tvBadge);
        }
    }
}
