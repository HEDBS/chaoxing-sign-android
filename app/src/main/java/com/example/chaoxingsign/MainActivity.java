package com.example.chaoxingsign;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 登录页: 输入手机号密码 -> 登录 -> RecyclerView 显示课程列表
 *
 * 注意: 网络请求必须在后台线程 (Android 主线程禁网), 用 Thread + runOnUiThread 回主线程更新 UI
 */
public class MainActivity extends AppCompatActivity {

    private EditText etPhone, etPassword;
    private TextView tvStatus;
    private Button btnLogin;
    private LinearLayout loginPanel;
    private RecyclerView rvCourses;
    private CourseAdapter courseAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Edge-to-edge: 内容避开状态栏/导航栏, 否则顶部按钮会被遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        etPhone = findViewById(R.id.etPhone);
        etPassword = findViewById(R.id.etPassword);
        tvStatus = findViewById(R.id.tvStatus);
        btnLogin = findViewById(R.id.btnLogin);
        loginPanel = findViewById(R.id.loginPanel);
        rvCourses = findViewById(R.id.rvCourses);

        // RecyclerView 必备三步: LayoutManager(布局方式) + Adapter(数据渲染)
        rvCourses.setLayoutManager(new LinearLayoutManager(this));

        btnLogin.setOnClickListener(v -> doLogin());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Android 13+: 请求通知权限(监听提醒需要)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        // 有已保存会话则自动登录(免输账号), 否则显示登录表单
        if (ChaoxingApi.loadSession(this)) {
            loadCoursesAuto();
        }
    }

    /** 恢复会话后自动拉课程列表 */
    private void loadCoursesAuto() {
        tvStatus.setText("恢复登录态, 加载课程中...");
        btnLogin.setEnabled(false);
        new Thread(() -> {
            String status;
            List<ChaoxingApi.Course> courses = null;
            try {
                courses = ChaoxingApi.instance.getCourses();
                status = "登录成功: " + ChaoxingApi.instance.getUserName()
                        + "，共 " + courses.size() + " 门课";
            } catch (Exception e) {
                status = "会话过期: " + e.getMessage();
                courses = null;
            }
            List<ChaoxingApi.Course> finalCourses = courses;
            String finalStatus = status;
            runOnUiThread(() -> {
                tvStatus.setText(finalStatus);
                if (finalCourses != null) {
                    showCourses(finalCourses);
                } else {
                    ChaoxingApi.clearSession(this); // 会话失效, 回登录页
                    loginPanel.setVisibility(View.VISIBLE);
                    rvCourses.setVisibility(View.GONE);
                    tvStatus.setText("未登录");
                    btnLogin.setEnabled(true);
                }
            });
        }).start();
    }

    /** 显示课程列表(登录成功后) */
    private void showCourses(List<ChaoxingApi.Course> courses) {
        loginPanel.setVisibility(View.GONE);
        rvCourses.setVisibility(View.VISIBLE);
        courseAdapter = new CourseAdapter(courses);
        courseAdapter.setOnCourseClickListener(course -> {
            Intent intent = new Intent(MainActivity.this, SignActivity.class);
            intent.putExtra("courseId", course.courseId);
            intent.putExtra("classId", course.classId);
            intent.putExtra("courseName", course.courseName);
            startActivity(intent);
        });
        rvCourses.setAdapter(courseAdapter);
    }

    /** 从设置页返回: 若已切换账号(instance 被清), 恢复登录表单 */
    @Override
    protected void onResume() {
        super.onResume();
        if (ChaoxingApi.instance == null && rvCourses.getVisibility() == View.VISIBLE) {
            loginPanel.setVisibility(View.VISIBLE);
            rvCourses.setVisibility(View.GONE);
            tvStatus.setText("未登录");
            btnLogin.setEnabled(true);
        }
    }

    private void doLogin() {
        String phone = etPhone.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入手机号和密码", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        tvStatus.setText("登录中...");

        // 网络请求放后台线程
        new Thread(() -> {
            String status;
            List<ChaoxingApi.Course> courses = null;
            String userName = "";
            ChaoxingApi api = null;
            try {
                api = new ChaoxingApi(phone, password);
                if (!api.login()) {
                    status = "登录失败: 手机号或密码错误";
                } else {
                    courses = api.getCourses();
                    userName = api.getUserName();
                    ChaoxingApi.instance = api; // 登录态全局复用
                    api.saveSession(MainActivity.this); // 持久化, 重启免登录
                    status = "登录成功: " + userName + "，共 " + courses.size() + " 门课";
                }
            } catch (Exception e) {
                status = "出错: " + e.getMessage();
            }

            // 回主线程更新 UI
            final List<ChaoxingApi.Course> finalCourses = courses;
            final String finalStatus = status;
                runOnUiThread(() -> {
                    tvStatus.setText(finalStatus);
                    if (finalCourses != null) {
                        // 登录成功: 隐藏登录表单, 显示课程列表
                        loginPanel.setVisibility(View.GONE);
                        rvCourses.setVisibility(View.VISIBLE);
                        showCourses(finalCourses);
                    } else {
                        btnLogin.setEnabled(true); // 登录失败可重试
                    }
                });
        }).start();
    }
}
