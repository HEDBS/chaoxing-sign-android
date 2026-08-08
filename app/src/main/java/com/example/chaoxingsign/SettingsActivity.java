package com.example.chaoxingsign;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 设置页: 监听开关 + 账号切换 + 自动签到选项(延时/保险)
 * 设置用 SharedPreferences 持久化, 监听服务读取
 */
public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "settings";
    public static final String KEY_DELAY_ENABLED = "delay_enabled";
    public static final String KEY_DELAY_SECONDS = "delay_seconds";
    public static final String KEY_INSURANCE_ENABLED = "insurance_enabled";
    public static final String KEY_INSURANCE_SECONDS = "insurance_seconds";

    private static final int REQ_NOTIFY = 100;

    private TextView tvAccount;
    private SwitchCompat swMonitor, swDelay, swInsurance;
    private EditText etDelaySeconds, etInsuranceSeconds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tvAccount = findViewById(R.id.tvAccount);
        swMonitor = findViewById(R.id.swMonitor);
        swDelay = findViewById(R.id.swDelay);
        swInsurance = findViewById(R.id.swInsurance);
        etDelaySeconds = findViewById(R.id.etDelaySeconds);
        etInsuranceSeconds = findViewById(R.id.etInsuranceSeconds);

        // 返回主页面
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 显示当前账号
        ChaoxingApi api = ChaoxingApi.instance;
        tvAccount.setText(api == null ? "未登录"
                : api.getPhone() + " (" + api.getUserName() + ")");

        // 读取已保存设置
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        swMonitor.setChecked(isServiceRunning(SignMonitorService.class));
        boolean delayOn = sp.getBoolean(KEY_DELAY_ENABLED, false);
        int delaySec = sp.getInt(KEY_DELAY_SECONDS, 30);
        boolean insuranceOn = sp.getBoolean(KEY_INSURANCE_ENABLED, false);
        int insuranceSec = sp.getInt(KEY_INSURANCE_SECONDS, 60);
        swDelay.setChecked(delayOn);
        etDelaySeconds.setText(String.valueOf(delaySec));
        swInsurance.setChecked(insuranceOn);
        etInsuranceSeconds.setText(String.valueOf(insuranceSec));

        // 监听开关: 启动/停止前台服务; 保险仅在监听模式下可用
        swMonitor.setOnCheckedChangeListener((btn, checked) -> {
            if (ChaoxingApi.instance == null) {
                Toast.makeText(this, "请先登录再开启监听", Toast.LENGTH_SHORT).show();
                swMonitor.setChecked(false);
                return;
            }
            if (checked) {
                requestNotifyPermission();
                SignMonitorService.start(this);
                Toast.makeText(this, "监听已开启", Toast.LENGTH_SHORT).show();
            } else {
                SignMonitorService.stop(this);
                Toast.makeText(this, "监听已关闭", Toast.LENGTH_SHORT).show();
            }
            updateInsuranceEnabled(checked); // 保险可用性跟随监听状态
        });

        // 延时/保险互斥(两者只能开一个, 与监听服务逻辑一致) + 即时保存
        swDelay.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && swInsurance.isChecked()) {
                swInsurance.setChecked(false); // 互斥: 开延时自动关保险
            }
            saveDelay();
        });
        swInsurance.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && swDelay.isChecked()) {
                swDelay.setChecked(false); // 互斥: 开保险自动关延时
            }
            saveInsurance();
        });
        etDelaySeconds.addTextChangedListener(new SaveWatcher(() -> saveDelay()));
        etInsuranceSeconds.addTextChangedListener(new SaveWatcher(() -> saveInsurance()));

        // 初始保险可用性: 跟随监听服务当前状态
        updateInsuranceEnabled(isServiceRunning(SignMonitorService.class));

        // 切换账号: 停监听 + 清登录态 -> 回主页面重新登录
        findViewById(R.id.btnSwitchAccount).setOnClickListener(v -> {
            SignMonitorService.stop(this);
            ChaoxingApi.clearSession(this); // 清内存 instance + 持久化会话
            Toast.makeText(this, "已退出, 请重新登录", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    /** 文本变化监听: 输入框内容变化后保存(避免每次按键都写盘, 延迟处理) */
    private static class SaveWatcher implements android.text.TextWatcher {
        private final Runnable action;
        SaveWatcher(Runnable action) { this.action = action; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            action.run();
        }
        @Override public void afterTextChanged(android.text.Editable s) { }
    }

    private void saveDelay() {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DELAY_ENABLED, swDelay.isChecked())
                .putInt(KEY_DELAY_SECONDS, parseOr(etDelaySeconds, 30))
                .apply();
    }

    private void saveInsurance() {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_INSURANCE_ENABLED, swInsurance.isChecked())
                .putInt(KEY_INSURANCE_SECONDS, parseOr(etInsuranceSeconds, 60))
                .apply();
    }

    /** 保险可用性: 仅监听模式下可开(保险依赖监听服务轮询) */
    private void updateInsuranceEnabled(boolean monitorOn) {
        swInsurance.setEnabled(monitorOn);
        etInsuranceSeconds.setEnabled(monitorOn);
        if (!monitorOn && swInsurance.isChecked()) {
            swInsurance.setChecked(false); // 监听关闭时保险自动关闭
        }
    }

    /** 监听开关状态与主页面同步(从设置页返回时刷新) */
    @Override
    protected void onResume() {
        super.onResume();
        swMonitor.setChecked(isServiceRunning(SignMonitorService.class));
        updateInsuranceEnabled(isServiceRunning(SignMonitorService.class));
    }

    private int parseOr(EditText et, int def) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Android 13+ 需要运行时通知权限 */
    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private boolean isServiceRunning(Class<?> cls) {
        android.app.ActivityManager am = (android.app.ActivityManager)
                getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(100)) {
            if (cls.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }
}
