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

    private TextView tvCourseName, tvActivity, tvResult, tvCodeLabel, tvGestureCode;
    private TextView tvGestureLoc, tvCodeLoc;
    private LinearLayout panelCode, panelLocation, panelQrcode, panelGesture;
    private EditText etCode, etLat, etLon, etAddress, etEnc;
    private GestureView gestureView;
    private Button btnSign, btnPickPhoto;
    private String gestureCode = ""; // 画板手势编码(手势签到用)
    private String uploadedObjectId = ""; // 拍照签到: 已上传图片的 objectId

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
        tvCodeLabel = findViewById(R.id.tvCodeLabel);
        panelCode = findViewById(R.id.panelCode);
        panelLocation = findViewById(R.id.panelLocation);
        panelQrcode = findViewById(R.id.panelQrcode);
        panelGesture = findViewById(R.id.panelGesture);
        etCode = findViewById(R.id.etCode);
        etLat = findViewById(R.id.etLat);
        etLon = findViewById(R.id.etLon);
        etAddress = findViewById(R.id.etAddress);
        etEnc = findViewById(R.id.etEnc);
        gestureView = findViewById(R.id.gestureView);
        tvGestureCode = findViewById(R.id.tvGestureCode);
        tvGestureLoc = findViewById(R.id.tvGestureLoc);
        tvCodeLoc = findViewById(R.id.tvCodeLoc);
        btnSign = findViewById(R.id.btnSign);
        btnPickPhoto = findViewById(R.id.btnPickPhoto);

        // 画板手势完成: 记录编码, 显示给用户确认
        gestureView.setOnGestureListener(code -> {
            gestureCode = code;
            tvGestureCode.setText("手势: " + code);
        });
        // 重画: 清空画板与编码
        findViewById(R.id.btnResetGesture).setOnClickListener(v -> {
            gestureCode = "";
            tvGestureCode.setText("");
            gestureView.clear();
        });
        // 地图选点: 打开选点页, 返回坐标+地址自动回填
        findViewById(R.id.btnPickLocation).setOnClickListener(v ->
                startActivityForResult(new android.content.Intent(this, LocationPickerActivity.class), 1001));

        // 拍照签到: 选择图片→上传云盘→记录 objectId
        btnPickPhoto.setOnClickListener(v ->
                startActivityForResult(
                        new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                                .setType("image/*")
                                .addCategory(android.content.Intent.CATEGORY_OPENABLE), 1002));

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
        // 输入框回车(IME 完成键)直接签到, 减少一次点击
        etCode.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                doSign();
                return true;
            }
            return false;
        });

        // 后台检测活动(或有 activeId 则直选)
        String activeId = getIntent().getStringExtra("activeId");
        if (activeId != null && !activeId.isEmpty()) {
            // 从列表弹窗选择: 直接用传入的活动信息
            int otherId = getIntent().getIntExtra("otherId", -1);
            String actName = getIntent().getStringExtra("actName");
            act = new ChaoxingApi.SignActivity();
            act.activeId = activeId;
            act.otherId = otherId;
            act.name = actName != null ? actName : "";
            act.courseId = courseId;
            act.classId = classId;
            tvActivity.setText("检测到签到: [" + ChaoxingApi.typeName(otherId) + "] " + act.name);
            setupParams(otherId);
            btnSign.setEnabled(true);
        } else {
            // 传统路径: 后台检测活动
            new Thread(() -> {
                String status;
                try {
                    act = api.checkActivity(courseId, classId, courseName);
                    status = act == null ? "当前无进行中的签到活动"
                            : "检测到签到: [" + ChaoxingApi.typeName(act.otherId) + "] " + act.name;
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
    }

    /** 按活动类型显示对应的参数输入区 */
    private void setupParams(int otherId) {
        boolean isCode = otherId == 5;        // 签到码: 数字输入
        boolean isGesture = otherId == 3;     // 手势: 九宫格画板
        panelCode.setVisibility(isCode ? View.VISIBLE : View.GONE);
        panelGesture.setVisibility(isGesture ? View.VISIBLE : View.GONE);
        panelLocation.setVisibility(otherId == 4 ? View.VISIBLE : View.GONE);
        panelQrcode.setVisibility(otherId == 2 ? View.VISIBLE : View.GONE);
        // 拍照签到(otherId==0 且 ifphoto): 显示选图上传按钮 (网络检查放后台线程)
        btnPickPhoto.setVisibility(otherId == 0 ? View.VISIBLE : View.GONE);
        if (otherId == 0) {
            btnPickPhoto.setEnabled(false);
            new Thread(() -> {
                boolean isPhoto = false;
                try { isPhoto = api.isPhotoSign(act.activeId); }
                catch (Exception e) {
                    android.util.Log.e("SignActivity", "isPhotoSign err: " + e.getMessage(), e);
                }
                final boolean fPhoto = isPhoto;
                runOnUiThread(() -> {
                    if (fPhoto) {
                        btnPickPhoto.setVisibility(View.VISIBLE);
                        btnPickPhoto.setEnabled(true);
                    } else {
                        btnPickPhoto.setVisibility(View.GONE); // 普通签到无拍照要求
                    }
                });
            }).start();
        }

        // 签到码: 自动聚焦数字键盘
        if (isCode) {
            etCode.requestFocus();
        }
        // 手势画板: 重置
        if (isGesture) {
            gestureCode = "";
            tvGestureCode.setText("");
            gestureView.clear();
        }
        // 手势/签到码若配置了默认位置: 显示提示(带位置提交时使用)
        String lat = defLat(), lon = defLon(), addr = defAddr();
        String locTip = "";
        if (!lat.isEmpty() && !lon.isEmpty()) {
            locTip = "📍 位置已启用，将使用默认位置签到：" + (addr.isEmpty() ? lat + "," + lon : addr)
                    + "（可在设置页修改）";
        } else {
            locTip = "⚠️ 本签到含位置校验，但未设置默认位置，请在设置页配置";
        }
        tvGestureLoc.setText(locTip);
        tvGestureLoc.setVisibility(isGesture ? View.VISIBLE : View.GONE);
        tvCodeLoc.setText(locTip);
        tvCodeLoc.setVisibility(isCode ? View.VISIBLE : View.GONE);
        // 位置: 从设置页加载默认位置自动预填(仅在字段为空时, 不覆盖地图选点/手动输入)
        if (otherId == 4) {
            if (etLat.getText().toString().trim().isEmpty()
                    && etLon.getText().toString().trim().isEmpty()
                    && etAddress.getText().toString().trim().isEmpty()) {
                android.content.SharedPreferences sp = getSharedPreferences(
                        SettingsActivity.PREF_NAME, MODE_PRIVATE);
                String defLat = sp.getString(SettingsActivity.KEY_DEFAULT_LAT, "");
                String defLon = sp.getString(SettingsActivity.KEY_DEFAULT_LON, "");
                String defAddr = sp.getString(SettingsActivity.KEY_DEFAULT_ADDRESS, "");
                if (!defLat.isEmpty() || !defLon.isEmpty()) {
                    etLat.setText(defLat);
                    etLon.setText(defLon);
                    etAddress.setText(defAddr);
                }
            }
        }
    }

    /** 地图选点返回: 坐标+地址回填; 图片选择返回: 上传云盘拿 objectId */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            etLat.setText(data.getStringExtra("lat"));
            etLon.setText(data.getStringExtra("lon"));
            etAddress.setText(data.getStringExtra("address"));
            tvResult.setText("已从地图选择位置");
        } else if (requestCode == 1002 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            uploadSelectedImage(data.getData());
        }
    }

    /** 读取所选图片 → 上传云盘 → 记录 objectId */
    private void uploadSelectedImage(android.net.Uri uri) {
        btnPickPhoto.setEnabled(false);
        tvResult.setText("正在上传图片...");
        new Thread(() -> {
            try {
                byte[] bytes = readUriBytes(uri);
                String oid = api.uploadPhoto(bytes, "0.png");
                if (oid == null || oid.isEmpty()) {
                    runOnUiThread(() -> {
                        tvResult.setText("上传失败, 请重试");
                        btnPickPhoto.setEnabled(true);
                    });
                    return;
                }
                uploadedObjectId = oid;
                runOnUiThread(() -> {
                    tvResult.setText("✅ 图片已上传, 可点击签到");
                    btnPickPhoto.setText("📷 已上传图片(可重新选择)");
                    btnPickPhoto.setEnabled(true);
                    btnSign.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvResult.setText("上传异常: " + e.getMessage());
                    btnPickPhoto.setEnabled(true);
                });
            }
        }).start();
    }

    private byte[] readUriBytes(android.net.Uri uri) throws Exception {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** 设置页默认纬度(手势/签到码带位置时用) */
    private String defLat() {
        return getSharedPreferences(SettingsActivity.PREF_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_DEFAULT_LAT, "");
    }

    private String defLon() {
        return getSharedPreferences(SettingsActivity.PREF_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_DEFAULT_LON, "");
    }

    private String defAddr() {
        return getSharedPreferences(SettingsActivity.PREF_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_DEFAULT_ADDRESS, "");
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
                    // 签到完成: 按钮置灰"已签到", 防重复签; 标记课程已签(列表徽标变灰) + 活动已签(多活动切换)
                    btnSign.setText("已签到");
                    btnSign.setEnabled(false);
                    MainActivity.signedCourses.add(act.courseId);
                    ChaoxingApi.signedActivityIds.add(act.activeId);
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
                    String oid = uploadedObjectId;
                    if (oid.isEmpty()) oid = api.getObjectId(); // 兜底: 云盘已有 0.png
                    if (oid == null || oid.isEmpty()) return "请先点击「📷 选择图片上传」上传签到图片";
                    return api.signPhoto(act, oid);
                }
                return api.signGeneral(act);
            case 3: // 手势(画板)
                if (gestureCode.isEmpty()) return "请在九宫格上画出老师的手势";
                return api.signWithCode(act, gestureCode, defLat(), defLon(), defAddr());
            case 5: // 签到码
                String code = etCode.getText().toString().trim();
                if (code.isEmpty()) return "请输入签到码";
                return api.signWithCode(act, code, defLat(), defLon(), defAddr());
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
                // 使用设置页的默认位置, 不再硬编码
                android.content.SharedPreferences sp = getSharedPreferences(
                        SettingsActivity.PREF_NAME, MODE_PRIVATE);
                String qrLat = sp.getString(SettingsActivity.KEY_DEFAULT_LAT, "34.817");
                String qrLon = sp.getString(SettingsActivity.KEY_DEFAULT_LON, "113.516");
                String qrAddr = sp.getString(SettingsActivity.KEY_DEFAULT_ADDRESS, "河南科技大学");
                return api.signQrcode(act, enc, qrLat, qrLon, qrAddr);
            default:
                return "不支持的签到类型";
        }
    }
}
