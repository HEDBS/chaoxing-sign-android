package com.example.chaoxingsign;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 地图选点页: 腾讯位置服务拾取坐标页(lbs.qq.com/getPoint/)
 * 点击地图自动显示坐标+地址(GCJ-02, 与学习通一致), 点"确定"回传
 *
 * 用法: startActivityForResult(LocationPickerActivity)
 * 返回: extra lat/lon/address
 */
public class LocationPickerActivity extends AppCompatActivity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_picker);

        // Edge-to-edge: 内容避开系统栏
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        webView = findViewById(R.id.wvMap);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "hermes");
        webView.loadUrl("https://lbs.qq.com/getPoint/");

        // 返回
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        // 确定: 注入 JS 读取选点结果回传
        findViewById(R.id.btnOk).setOnClickListener(v -> pickResult());
    }

    /** 注入 JS: 读页面选点输入框, 通过 JS 接口回传 */
    @SuppressLint("JavascriptInterface")
    private void pickResult() {
        String js = "(function(){"
                + "var ins=document.querySelectorAll('input');"
                + "var coord=ins.length>2?ins[2].value:'';"
                + "var addr=ins.length>3?ins[3].value:'';"
                + "window.hermes.onPick(coord,addr);"
                + "})()";
        webView.evaluateJavascript(js, null);
    }

    /** JS 接口: 接收选点结果(坐标, 地址) */
    private class JsBridge {
        @JavascriptInterface
        public void onPick(final String coord, final String addr) {
            runOnUiThread(() -> {
                if (coord == null || !coord.contains(",")) {
                    Toast.makeText(LocationPickerActivity.this,
                            "请先在地图上点击选择位置", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] xy = coord.split(",");
                if (xy.length < 2) {
                    Toast.makeText(LocationPickerActivity.this,
                            "坐标格式异常", Toast.LENGTH_SHORT).show();
                    return;
                }
                getIntent().putExtra("lat", xy[0].trim());
                getIntent().putExtra("lon", xy[1].trim());
                getIntent().putExtra("address", addr == null ? "" : addr.trim());
                setResult(RESULT_OK, getIntent());
                finish();
            });
        }
    }
}
