package com.example.chaoxingsign;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
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

    /** 本机已签过的课程(签到页标记): 徽标变灰"已签到", 不再置顶 */
    public static final java.util.Set<String> signedCourses = new java.util.HashSet<>();

    private EditText etPhone, etPassword;
    private TextView tvStatus, tvMonitor, tvEmpty;
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
        tvMonitor = findViewById(R.id.tvMonitor);
        tvEmpty = findViewById(R.id.tvEmpty);
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
                    detectAndShow(finalCourses);
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

    /** 显示课程列表(登录成功后), 课程为空时显示空状态提示 */
    private void showCourses(List<ChaoxingApi.Course> courses) {
        loginPanel.setVisibility(View.GONE);
        if (courses == null || courses.isEmpty()) {
            rvCourses.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        rvCourses.setVisibility(View.VISIBLE);
        courseAdapter = new CourseAdapter(courses);
        courseAdapter.setOnCourseClickListener(course -> {
            // 先检测活动数量: 多个则弹窗选, 一个直进, 无则提示
            new Thread(() -> {
                try {
                    java.util.List<ChaoxingApi.SignActivity> acts =
                            ChaoxingApi.instance.getActiveActivities(course.courseId, course.classId, course.courseName);
                    int count = acts == null ? 0 : acts.size();
                    runOnUiThread(() -> {
                        if (count == 0) {
                            Toast.makeText(MainActivity.this, "当前无进行中的签到活动", Toast.LENGTH_SHORT).show();
                        } else {
                            showActivityPicker(acts, course.courseName);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "检测失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        });
        rvCourses.setAdapter(courseAdapter);
    }

    /** 直接打开签到页(单个活动) */
    private void openSignActivity(ChaoxingApi.SignActivity act, String courseName) {
        Intent intent = new Intent(MainActivity.this, SignActivity.class);
        intent.putExtra("activeId", act.activeId);
        intent.putExtra("otherId", act.otherId);
        intent.putExtra("actName", act.name);
        intent.putExtra("courseId", act.courseId);
        intent.putExtra("classId", act.classId);
        intent.putExtra("courseName", courseName);
        startActivity(intent);
    }

    /** 多活动选择对话框: 列出所有进行中活动(含已签)供用户选择 */
    private void showActivityPicker(java.util.List<ChaoxingApi.SignActivity> acts, String courseName) {
        String[] items = new String[acts.size()];
        for (int i = 0; i < acts.size(); i++) {
            ChaoxingApi.SignActivity a = acts.get(i);
            String time = formatTime(a.startTime);
            String status = a.signed ? " ✅ 已签" : "";
            String sid = a.activeId.length() > 4 ? a.activeId.substring(a.activeId.length() - 4) : a.activeId;
            items[i] = "[" + ChaoxingApi.typeName(a.otherId) + "] " + a.name + " · " + time + " #" + sid + status;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(courseName + " · 选择签到活动")
                .setItems(items, (dialog, which) -> {
                    ChaoxingApi.SignActivity a = acts.get(which);
                    if (a.signed) {
                        Toast.makeText(this, "该活动已签过", Toast.LENGTH_SHORT).show();
                    } else {
                        openSignActivity(a, courseName);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String formatTime(long ts) {
        if (ts == 0) return "--:--";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(ts));
    }

    /**
     * 扫描每门课的活动状态: 有进行中签到的课程标记 + 置顶显示
     * 后台线程执行(27 门课约 2-3 秒), 完成后回主线程更新 UI
     */
    private void detectAndShow(List<ChaoxingApi.Course> courses) {
        tvStatus.setText("检测签到状态中...");
        scanAndRefresh(courses, true);
    }

    /** 刷新活动状态(从签到页返回/活动结束后, 高亮与置顶自动复原) */
    private void refreshActivities() {
        if (courseAdapter == null || rvCourses.getVisibility() != View.VISIBLE) return;
        scanAndRefresh(courseAdapter.getCourses(), false);
    }

    private volatile boolean scanning = false; // 防并发扫描

    private void scanAndRefresh(List<ChaoxingApi.Course> courses, boolean firstTime) {
        if (scanning) return; // 上一次扫描未完成则跳过
        scanning = true;
        new Thread(() -> {
            int found = 0; // 未签的活动数
            for (ChaoxingApi.Course c : courses) {
                try {
                    c.hasActivity = ChaoxingApi.instance.checkActivity(
                            c.courseId, c.classId, c.courseName) != null;
                    // 有未签活动则清除已签标记(同课多活动场景)
                    c.signed = !c.hasActivity && signedCourses.contains(c.courseId);
                    if (c.hasActivity && !c.signed) found++; // 仅未签的活动算"有签到"
                } catch (Exception ignored) {
                    c.hasActivity = false;
                }
            }
            // 排序: 未签的活动课置顶, 已签/无活动保持原顺序
            List<ChaoxingApi.Course> sorted = new java.util.ArrayList<>(courses);
            sorted.sort((a, b) -> {
                boolean ua = a.hasActivity && !a.signed;
                boolean ub = b.hasActivity && !b.signed;
                return Boolean.compare(ub, ua);
            });
            int finalFound = found;
            scanning = false;
            runOnUiThread(() -> {
                tvStatus.setText("登录成功: " + ChaoxingApi.instance.getUserName()
                        + "，共 " + courses.size() + " 门课"
                        + (finalFound > 0 ? " · " + finalFound + " 门有签到" : ""));
                if (firstTime) {
                    showCourses(sorted);
                } else {
                    courseAdapter.update(sorted); // 高亮/置顶随活动状态复原
                }
            });
        }).start();
    }

    /** 从设置页返回: 若已切换账号(instance 被清), 恢复登录表单; 并刷新监听状态 */
    @Override
    protected void onResume() {
        super.onResume();
        refreshMonitorStatus();
        if (ChaoxingApi.instance == null && rvCourses.getVisibility() == View.VISIBLE) {
            loginPanel.setVisibility(View.VISIBLE);
            rvCourses.setVisibility(View.GONE);
            tvStatus.setText("未登录");
            btnLogin.setEnabled(true);
        } else {
            // 签到返回/活动结束: 刷新活动状态, 高亮与置顶自动复原
            refreshActivities();
        }
    }

    /** 监听状态条: 后台服务运行中 -> 绿色提示, 否则灰色提示 */
    private void refreshMonitorStatus() {
        boolean running = false;
        android.app.ActivityManager am = (android.app.ActivityManager)
                getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(100)) {
                if (SignMonitorService.class.getName().equals(info.service.getClassName())) {
                    running = true;
                    break;
                }
            }
        }
        if (running) {
            tvMonitor.setText("📡 监听中 · 每 60 秒检测签到");
            tvMonitor.setTextColor(0xFF2E7D32);
        } else {
            // 检查是否之前开启过监听(持久化), 自动恢复
            SharedPreferences sp = getSharedPreferences(SettingsActivity.PREF_NAME, MODE_PRIVATE);
            if (sp.getBoolean(SettingsActivity.KEY_MONITOR_ENABLED, false) && ChaoxingApi.instance != null) {
                SignMonitorService.start(this);
                tvMonitor.setText("📡 监听中 · 每 60 秒检测签到");
                tvMonitor.setTextColor(0xFF2E7D32);
                return;
            }
            tvMonitor.setText("🔕 监听已关闭 · 可在设置中开启");
            tvMonitor.setTextColor(0xFF9E9E9E);
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
                        detectAndShow(finalCourses);
                    } else {
                        btnLogin.setEnabled(true); // 登录失败可重试
                    }
                });
        }).start();
    }
}
