package com.example.chaoxingsign;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 签到页: 进入时检测该课程当前是否有进行中的签到
 *  - 有活动: 显示类型, 按类型显示参数输入区, 点"签到"提交
 *  - 无活动: 提示, 按钮禁用
 */
public class SignActivity extends AppCompatActivity {

    private TextView tvCourseName, tvActivity, tvResult;
    private LinearLayout panelCode, panelLocation, panelQrcode;
    private EditText etCode, etLat, etLon, etAddress, etEnc;
    private Button btnSign;

    private ChaoxingApi api;
    private ChaoxingApi.SignActivity act; // 当前检测到的活动

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign);

        // Edge-to-edge: 内容避开系统栏(状态栏/导航栏), 否则顶部按钮会被遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tvCourseName = findViewById(R.id.tvCourseName);
        tvActivity = findViewById(R.id.tvActivity);
        tvResult = findViewById(R.id.tvResult);
        panelCode = findViewById(R.id.panelCode);
        panelLocation = findViewById(R.id.panelLocation);
        panelQrcode = findViewById(R.id.panelQrcode);
        etCode = findViewById(R.id.etCode);
        etLat = findViewById(R.id.etLat);
        etLon = findViewById(R.id.etLon);
        etAddress = findViewById(R.id.etAddress);
        etEnc = findViewById(R.id.etEnc);
        btnSign = findViewById(R.id.btnSign);

        // 返回课程列表
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String courseId = getIntent().getStringExtra("courseId");
        String classId = getIntent().getStringExtra("classId");
        String courseName = getIntent().getStringExtra("courseName");
        tvCourseName.setText(courseName == null ? "" : courseName);

        // 登录态防护: 会话过期时回主页面重新登录, 避免空指针崩溃
        api = ChaoxingApi.instance;
        if (api == null) {
            tvActivity.setText("登录已过期, 请返回重新登录");
            btnSign.setEnabled(false);
            return;
        }

        btnSign.setOnClickListener(v -> doSign());

        // 后台检测活动
        new Thread(() -> {
            String status;
            try {
                act = api.checkActivity(courseId, classId);
                status = act == null ? "当前无进行中的签到活动"
                        : "检测到签到: [" + typeName(act.otherId) + "] " + act.name;
            } catch (Exception e) {
                status = "检测出错: " + e.getMessage();
            }
            final String finalStatus = status;
            runOnUiThread(() -> {
                tvActivity.setText(finalStatus);
                if (act != null) {
                    setupParams(act.otherId);
                    btnSign.setEnabled(true);
                }
            });
        }).start();
    }

    /** 按活动类型显示对应的参数输入区 */
    private void setupParams(int otherId) {
        panelCode.setVisibility(otherId == 3 || otherId == 5 ? View.VISIBLE : View.GONE);
        panelLocation.setVisibility(otherId == 4 ? View.VISIBLE : View.GONE);
        panelQrcode.setVisibility(otherId == 2 ? View.VISIBLE : View.GONE);
    }

    private static String typeName(int otherId) {
        switch (otherId) {
            case 0: return "普通/拍照";
            case 2: return "二维码";
            case 3: return "手势";
            case 4: return "位置";
            case 5: return "签到码";
            default: return "未知";
        }
    }

    /** 按类型执行签到 (对应 Python 版 do_sign) */
    private void doSign() {
        if (act == null) return;
        btnSign.setEnabled(false);
        tvResult.setText("签到中...");

        new Thread(() -> {
            String msg;
            try {
                msg = executeSign();
            } catch (Exception e) {
                msg = "出错: " + e.getMessage();
            }
            String finalMsg = msg;
            runOnUiThread(() -> {
                // 服务端返回 success 即签到成功, 转成友好中文提示
                boolean ok = "success".equals(finalMsg.trim());
                boolean already = finalMsg.contains("已签到");
                tvResult.setText(ok ? "✅ 签到成功!" : finalMsg);
                if (ok || already) {
                    // 签到完成: 按钮置灰"已签到", 防止重复签
                    btnSign.setText("已签到");
                    btnSign.setEnabled(false);
                } else {
                    btnSign.setEnabled(true); // 失败可重试
                }
            });
        }).start();
    }

    private String executeSign() throws Exception {
        api.preSign(act);
        switch (act.otherId) {
            case 0: // 普通/拍照
                if (api.isPhotoSign(act.activeId)) {
                    String oid = api.getObjectId();
                    if (oid == null) return "云盘根目录未找到 0.jpg/0.png, 请先上传";
                    return api.signPhoto(act, oid);
                }
                return api.signGeneral(act);
            case 3: case 5: // 手势/签到码
                String code = etCode.getText().toString().trim();
                if (code.isEmpty()) return "请输入签到码/手势码";
                return api.signWithCode(act, code);
            case 4: // 位置
                String lat = etLat.getText().toString().trim();
                String lon = etLon.getText().toString().trim();
                String address = etAddress.getText().toString().trim();
                if (lat.isEmpty() || lon.isEmpty() || address.isEmpty()) {
                    return "请填写完整的纬度/经度/地址";
                }
                return api.signLocation(act, lat, lon, address);
            case 2: // 二维码
                String enc = etEnc.getText().toString().trim();
                if (enc.isEmpty()) return "请输入二维码 enc";
                return api.signQrcode(act, enc, "34.817", "113.516", "河南科技大学");
            default:
                return "不支持的签到类型";
        }
    }
}
