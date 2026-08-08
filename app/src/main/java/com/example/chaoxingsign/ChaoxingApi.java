package com.example.chaoxingsign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 超星学习通协议层 (从已实测通过的 Python 版翻译)
 *
 * 用法: 在后台线程调用 (Android 主线程禁止网络请求)
 *   ChaoxingApi api = new ChaoxingApi("手机号", "密码");
 *   boolean ok = api.login();
 *   List<ChaoxingApi.Course> courses = api.getCourses();
 */
public class ChaoxingApi {

    /** 全局登录实例: 登录成功后由 MainActivity 设置, 其他页面直接复用(免重复登录) */
    public static ChaoxingApi instance;

    // ============ 常量 ============
    private static final String BASE = "https://mobilelearn.chaoxing.com";
    private static final String DES_KEY = "u2oh6Vu^"; // 前端硬编码16字节, DES取前8字节
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; 22081212C Build/TKQ1.220829.002; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/107.0.0.0 Mobile Safari/537.36";

    // ============ 数据类 ============
    public static class Course {
        public String courseId, classId, chatid, courseName, className;
    }

    public static class SignActivity implements java.io.Serializable {
        public String activeId, name, courseId, classId;
        public int otherId; // 0=普通/拍照 2=二维码 3=手势 4=位置 5=签到码
        @Override
        public String toString() {
            return "[" + name + "] activeId=" + activeId + " otherId=" + otherId;
        }
    }

    // ============ 状态 ============
    private final String phone;
    private final String password;
    private String uid = "", fid = "-1", userName = "";
    private final Map<String, String> cookies = new HashMap<>();

    public ChaoxingApi(String phone, String password) {
        this.phone = phone;
        this.password = password;
    }

    public String getUserName() { return userName; }
    public String getUid() { return uid; }
    public String getPhone() { return phone; }

    // ============ 会话持久化 (SharedPreferences) ============
    private static final String SESSION_PREF = "session";

    /** 登录成功后保存会话, 重启 App 免登录 */
    public void saveSession(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        ctx.getSharedPreferences(SESSION_PREF, Context.MODE_PRIVATE).edit()
                .putString("phone", phone)
                .putString("uid", uid)
                .putString("fid", fid)
                .putString("name", userName)
                .putString("cookies", sb.toString())
                .apply();
    }

    /** 恢复会话: 成功则设置 instance 并返回 true */
    public static boolean loadSession(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(SESSION_PREF, Context.MODE_PRIVATE);
        if (!sp.contains("uid")) return false;
        ChaoxingApi api = new ChaoxingApi(sp.getString("phone", ""), "");
        api.uid = sp.getString("uid", "");
        api.fid = sp.getString("fid", "-1");
        api.userName = sp.getString("name", "");
        for (String kv : sp.getString("cookies", "").split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0) api.cookies.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        instance = api;
        return true;
    }

    /** 清除会话(切换账号时) */
    public static void clearSession(Context ctx) {
        ctx.getSharedPreferences(SESSION_PREF, Context.MODE_PRIVATE).edit().clear().apply();
        instance = null;
    }

    // ============ 登录 ============
    public boolean login() throws Exception {
        String enc = desEncrypt(password);
        String form = "uname=" + phone + "&password=" + enc
                + "&fid=-1&t=true&refer=https%253A%252F%252Fi.chaoxing.com"
                + "&forbidotherlogin=0&validate=";
        HttpURLConnection conn = openPost("https://passport2.chaoxing.com/fanyalogin", form,
                "application/x-www-form-urlencoded", true);
        String body = readBody(conn);

        // 保存 set-cookie
        Map<String, List<String>> headers = conn.getHeaderFields();
        List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies != null) {
            for (String sc : setCookies) {
                int semi = sc.indexOf(';');
                String kv = semi > 0 ? sc.substring(0, semi) : sc;
                int eq = kv.indexOf('=');
                if (eq > 0) cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        conn.disconnect();

        JSONObject res = new JSONObject(body);
        if (!res.optBoolean("status", false)) return false;

        uid = cookies.getOrDefault("_uid", "");
        fid = cookies.getOrDefault("fid", "-1"); // 学校ID, 签到参数需要
        userName = res.optString("name", "");
        if (userName.isEmpty()) userName = fetchName();
        return true;
    }

    /** 从 accountManage 页面提取真实姓名 */
    private String fetchName() throws Exception {
        String html = get("https://passport2.chaoxing.com/mooc/accountManage", false);
        Matcher m = Pattern.compile("class=\"fr colorBlue\"[^>]*>\\s*([^<\\s]+)\\s*</p>").matcher(html);
        return m.find() ? m.group(1).trim() : "";
    }

    // ============ 课程列表 (backclazzdata) ============
    public List<Course> getCourses() throws Exception {
        String json = get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?rss=1", true);
        List<Course> list = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray channels = root.optJSONArray("channelList");
        if (channels == null) return list;

        for (int i = 0; i < channels.length(); i++) {
            JSONObject ch = channels.getJSONObject(i);
            JSONObject content = ch.optJSONObject("content");
            if (content == null) continue;
            String classId = String.valueOf(content.optLong("id", 0)); // content.id = classId
            String chatid = content.optString("chatid", "");
            String clsName = content.optString("name", "");
            JSONObject course = content.optJSONObject("course");
            JSONArray data = course == null ? null : course.optJSONArray("data");
            if (data == null) continue;
            for (int j = 0; j < data.length(); j++) {
                JSONObject c = data.getJSONObject(j);
                Course item = new Course();
                item.courseId = String.valueOf(c.optLong("id", 0));
                item.classId = classId;
                item.chatid = chatid;
                item.courseName = c.optString("name", "");
                item.className = clsName;
                list.add(item);
            }
        }
        return list;
    }

    // ============ 活动检测 ============
    public SignActivity checkActivity(String courseId, String classId) throws Exception {
        String url = BASE + "/v2/apis/active/student/activelist?fid=0&courseId=" + courseId
                + "&classId=" + classId + "&_=" + System.currentTimeMillis();
        JSONObject root = new JSONObject(get(url, true));
        JSONObject data = root.optJSONObject("data");
        if (data == null) return null;
        JSONArray list = data.optJSONArray("activeList");
        if (list == null || list.length() == 0) return null;

        JSONObject a = list.getJSONObject(0);
        int otherId = a.optInt("otherId", -1);
        int status = a.optInt("status", -1);
        if (status != 1 || otherId < 0 || otherId > 5) return null;
        long startTime = a.optLong("startTime", 0);
        if (System.currentTimeMillis() - startTime > 7200_000L) return null; // 开始超2小时忽略

        SignActivity act = new SignActivity();
        act.activeId = String.valueOf(a.optLong("id", 0));
        act.name = a.optString("nameOne", "");
        act.otherId = otherId;
        act.courseId = courseId;
        act.classId = classId;
        return act;
    }

    // ============ 预签到 ============
    public void preSign(SignActivity act) throws Exception {
        get(BASE + "/newsign/preSign?courseId=" + act.courseId + "&classId=" + act.classId
                + "&activePrimaryId=" + act.activeId + "&general=1&sys=1&ls=1&appType=15"
                + "&&tid=&uid=" + uid + "&ut=s", false);
        String analysis = get(BASE + "/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM&aid=" + act.activeId, false);
        Matcher m = Pattern.compile("code='\\+'([^']*)").matcher(analysis);
        if (m.find()) {
            get(BASE + "/pptSign/analysis2?DB_STRATEGY=RANDOM&code=" + m.group(1), false);
        }
        Thread.sleep(500);
    }

    // ============ 签到提交 ============
    public String signGeneral(SignActivity act) throws Exception {
        String url = BASE + "/pptSign/stuSignajax?activeId=" + act.activeId + "&uid=" + uid
                + "&clientip=&latitude=-1&longitude=-1&appType=15&fid=" + fid
                + "&name=" + enc(userName);
        return get(url, false);
    }

    /** 手势/签到码: 先 checkSignCode 校验, 通过后提交 */
    public String signWithCode(SignActivity act, String signCode) throws Exception {
        String check = get("https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/"
                + "checkSignCode?activeId=" + act.activeId + "&signCode=" + enc(signCode), false);
        JSONObject res = new JSONObject(check);
        if (res.optInt("result", 0) != 1) {
            return "码校验失败: " + res.optString("errorMsg", check);
        }
        Thread.sleep(200);
        String url = BASE + "/pptSign/stuSignajax?activeId=" + act.activeId + "&uid=" + uid
                + "&clientip=&latitude=&longitude=&appType=15&fid=" + fid
                + "&name=" + enc(userName) + "&signCode=" + enc(signCode);
        return get(url, false);
    }

    public String signLocation(SignActivity act, String lat, String lon, String address) throws Exception {
        String url = BASE + "/pptSign/stuSignajax?name=" + enc(userName)
                + "&address=" + enc(address) + "&activeId=" + act.activeId + "&uid=" + uid
                + "&clientip=&latitude=" + lat + "&longitude=" + lon
                + "&fid=" + fid + "&appType=15&ifTiJiao=1";
        return get(url, false);
    }

    public String signPhoto(SignActivity act, String objectId) throws Exception {
        String url = BASE + "/pptSign/stuSignajax?activeId=" + act.activeId + "&uid=" + uid
                + "&clientip=&useragent=&latitude=-1&longitude=-1&appType=15&fid=" + fid
                + "&objectId=" + objectId + "&name=" + enc(userName);
        return get(url, false);
    }

    public String signQrcode(SignActivity act, String encCode, String lat, String lon, String address) throws Exception {
        String loc = "{\"result\":\"1\",\"address\":\"" + address + "\",\"latitude\":" + lat
                + ",\"longitude\":" + lon + ",\"altitude\":100}";
        String url = BASE + "/pptSign/stuSignajax?enc=" + encCode + "&name=" + enc(userName)
                + "&activeId=" + act.activeId + "&uid=" + uid
                + "&clientip=&location=" + enc(loc) + "&latitude=-1&longitude=-1"
                + "&fid=" + fid + "&appType=15";
        return get(url, false);
    }

    /** otherId==0 时判断是否拍照签到 */
    public boolean isPhotoSign(String activeId) throws Exception {
        String json = get(BASE + "/v2/apis/active/getPPTActiveInfo?activeId=" + activeId, false);
        JSONObject data = new JSONObject(json).optJSONObject("data");
        return data != null && data.optInt("ifphoto", 0) == 1;
    }

    /** 从云盘根目录找 0.jpg/0.png 的 objectId */
    public String getObjectId() throws Exception {
        String pan = get("https://pan-yz.chaoxing.com", false);
        Matcher mEnc = Pattern.compile("enc =\"(.*?)\"").matcher(pan);
        Matcher mRoot = Pattern.compile("_rootdir = \"(.*?)\"").matcher(pan);
        if (!mEnc.find() || !mRoot.find()) return null;
        String encVal = mEnc.group(1), parentId = mRoot.group(1);
        String params = "puid=0&shareid=0&parentId=" + parentId + "&page=1&size=50&enc=" + encVal;
        String json = post("https://pan-yz.chaoxing.com/opt/listres?" + params, params,
                "application/x-www-form-urlencoded", false);
        JSONArray list = new JSONObject(json).optJSONArray("list");
        if (list == null) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.getJSONObject(i);
            String fn = f.optString("name", "");
            if (fn.equals("0.jpg") || fn.equals("0.png")) return f.optString("objectId");
        }
        return null;
    }

    // ============ HTTP 工具 ============
    private String get(String url, boolean acceptJson) throws Exception {
        return request(url, "GET", null, null, acceptJson);
    }

    private HttpURLConnection openPost(String url, String form, String contentType, boolean withHeader) throws Exception {
        return (HttpURLConnection) open(url, "POST", form, contentType, withHeader);
    }

    private String post(String url, String form, String contentType, boolean withHeader) throws Exception {
        HttpURLConnection conn = openPost(url, form, contentType, withHeader);
        String body = readBody(conn);
        conn.disconnect();
        return body;
    }

    private String request(String url, String method, String form, String contentType, boolean acceptJson) throws Exception {
        HttpURLConnection conn = open(url, method, form, contentType, acceptJson);
        String body = readBody(conn);
        conn.disconnect();
        return body;
    }

    private HttpURLConnection open(String url, String method, String form, String contentType, boolean withHeader) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", withHeader ? "application/json, text/plain, */*" : "*/*");
        conn.setRequestProperty("Accept-Language", "zh-cn");
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
        // Cookie
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append("; ");
        }
        if (sb.length() > 0) conn.setRequestProperty("Cookie", sb.toString());
        // POST body
        if ("POST".equals(method) && form != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    // ============ DES 加密 (密码) ============
    private static String desEncrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(DES_KEY.getBytes(StandardCharsets.UTF_8), "DES"));
        return toHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
