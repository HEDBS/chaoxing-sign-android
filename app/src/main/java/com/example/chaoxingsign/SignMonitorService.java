package com.example.chaoxingsign;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 监听服务(前台): 每 60 秒轮询全部课程, 检测到签到自动处理
 *  - 普通/拍照: 按设置延时签到 / 立即签, 保险模式下超时未确认自动签
 *  - 手势/签到码/位置/二维码: 通知用户点开签到页手动签
 *
 * 设置: 设置页的延时/保险项; 需登录态 (ChaoxingApi.instance)
 */
public class SignMonitorService extends Service {

    public static final String ACTION_STOP = "com.example.chaoxingsign.STOP";
    private static final String CHANNEL_ID = "monitor_channel";
    private static final int NOTIF_MONITOR = 1; // 常驻前台通知
    private static final int NOTIF_ALERT = 2;   // 签到提醒通知
    private static final long INTERVAL = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false; // 轮询线程开关
    private ChaoxingApi api;
    private List<ChaoxingApi.Course> courses;
    private final Set<String> handled = new HashSet<>(); // 已处理活动, 去重

    private boolean delayEnabled, insuranceEnabled;
    private int delaySeconds, insuranceSeconds;

    /** 独立后台线程轮询: 主线程做网络 IO 会导致 okhttp 连接失败 */
    private final Thread monitorThread = new Thread(() -> {
        while (running) {
            try {
                scan();
            } catch (Exception e) {
                android.util.Log.e("SignMonitor", "轮询异常: " + e.getMessage());
            }
            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                break;
            }
        }
    }, "SignMonitor-Thread");

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        loadSettings();
        startForeground(NOTIF_MONITOR, buildMonitorNotification());

        // 取登录态: 内存 instance 优先, 丢失则尝试恢复持久化会话
        api = ChaoxingApi.instance;
        if (api == null) {
            ChaoxingApi.loadSession(this);
            api = ChaoxingApi.instance;
        }
        if (api == null) {
            android.util.Log.e("SignMonitor", "无登录态, 服务停止");
            stopSelf();
            return;
        }
        android.util.Log.d("SignMonitor", "服务启动, 加载课程列表");
        // 后台线程拉课程列表, 然后启动轮询线程
        new Thread(() -> {
            try {
                courses = api.getCourses();
                android.util.Log.d("SignMonitor", "课程加载完成: " + (courses == null ? 0 : courses.size()));
            } catch (Exception e) {
                android.util.Log.e("SignMonitor", "课程加载失败: " + e.getMessage());
            }
            if (courses != null) {
                running = true;
                monitorThread.start();
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getBooleanExtra("sign_now", false)) {
            // 通知按钮"立即签到"
            ChaoxingApi.SignActivity act = (ChaoxingApi.SignActivity)
                    intent.getSerializableExtra("act");
            if (act != null) autoSign(act);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        monitorThread.interrupt();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(NOTIF_ALERT);
        android.util.Log.d("SignMonitor", "服务停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============ 轮询 ============
    private void scan() {
        if (api == null || courses == null) return;
        android.util.Log.d("SignMonitor", "轮询 " + courses.size() + " 门课");
        int ok = 0, fail = 0;
        for (ChaoxingApi.Course c : courses) {
            try {
                ChaoxingApi.SignActivity act = api.checkActivity(c.courseId, c.classId);
                ok++;
                if (act != null && !handled.contains(act.activeId)) {
                    handled.add(act.activeId);
                    android.util.Log.d("SignMonitor", "检测到签到: " + act.name + " type=" + act.otherId);
                    handleActivity(act);
                }
            } catch (Exception e) {
                fail++;
                if (fail <= 3) { // 最多记 3 条失败日志, 避免刷屏
                    android.util.Log.e("SignMonitor", "课程[" + c.courseName + "]检测失败: " + e.getMessage());
                }
            }
        }
        android.util.Log.d("SignMonitor", "本轮完成 ok=" + ok + " fail=" + fail);
    }

    /** 按活动类型处理: 可自动签(普通/拍照)走延时/保险; 需参数类型通知手动 */
    private void handleActivity(ChaoxingApi.SignActivity act) {
        if (act.otherId == 0) {
            if (delayEnabled) {
                notifyAlert(act, "检测到签到: " + act.name,
                        delaySeconds + " 秒后自动签到", true);
                handler.postDelayed(() -> autoSign(act), delaySeconds * 1000L);
            } else if (insuranceEnabled) {
                notifyAlert(act, "检测到签到: " + act.name,
                        "点击确认, " + insuranceSeconds + " 秒未确认将自动签", true);
                handler.postDelayed(() -> autoSign(act), insuranceSeconds * 1000L);
            } else {
                autoSign(act); // 立即签
            }
        } else {
            notifyManual(act);
        }
    }

    /** 自动签到(普通/拍照), 签到完成发结果通知 */
    private void autoSign(ChaoxingApi.SignActivity act) {
        new Thread(() -> {
            String msg;
            try {
                api.preSign(act);
                if (api.isPhotoSign(act.activeId)) {
                    String oid = api.getObjectId();
                    msg = oid == null ? "云盘未找到 0.jpg/0.png"
                            : api.signPhoto(act, oid);
                } else {
                    msg = api.signGeneral(act);
                }
            } catch (Exception e) {
                msg = "出错: " + e.getMessage();
            }
            notifyResult(act, msg);
        }).start();
    }

    // ============ 通知 ============
    private void createChannel() {
        // NotificationChannel 仅 API 26+ (minSdk 24 需版本判断)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "签到监听",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("监听签到活动");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildMonitorNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("签到监听中")
                .setContentText("每 60 秒检测签到活动")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** 可自动签提醒: 点击打开签到页确认, 带"立即签到"动作 */
    private void notifyAlert(ChaoxingApi.SignActivity act, String title, String text, boolean withAction) {
        Intent open = new Intent(this, SignActivity.class);
        open.putExtra("courseId", act.courseId);
        open.putExtra("classId", act.classId);
        open.putExtra("courseName", act.name);
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(),
                open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true);
        if (withAction) {
            Intent sign = new Intent(this, SignMonitorService.class);
            sign.putExtra("act", act);
            PendingIntent spi = PendingIntent.getService(this, (int) System.currentTimeMillis(),
                    sign, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(0, "立即签到", spi);
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ALERT, b.build());
    }

    /** 需手动签到(手势/签到码/位置/二维码) */
    private void notifyManual(ChaoxingApi.SignActivity act) {
        String type = act.otherId == 2 ? "二维码" : act.otherId == 3 ? "手势"
                : act.otherId == 4 ? "位置" : "签到码";
        notifyAlert(act, "检测到" + type + "签到: " + act.name,
                "点击前往手动签到", false);
    }

    /** 签到结果通知 */
    private void notifyResult(ChaoxingApi.SignActivity act, String msg) {
        boolean ok = "success".equals(msg.trim());
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(ok ? "✅ 签到成功" : "⚠️ 签到失败")
                .setContentText(act.name + ": " + (ok ? "已自动签到" : msg))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_ALERT, n);
    }

    // ============ 工具 ============
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences(SettingsActivity.PREF_NAME,
                Context.MODE_PRIVATE);
        delayEnabled = sp.getBoolean(SettingsActivity.KEY_DELAY_ENABLED, false);
        delaySeconds = sp.getInt(SettingsActivity.KEY_DELAY_SECONDS, 30);
        insuranceEnabled = sp.getBoolean(SettingsActivity.KEY_INSURANCE_ENABLED, false);
        insuranceSeconds = sp.getInt(SettingsActivity.KEY_INSURANCE_SECONDS, 60);
    }

    /** 设置页开关: 启动/停止监听 */
    public static void start(Context ctx) {
        // startForegroundService 仅 API 26+, 低版本用 startService
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(new Intent(ctx, SignMonitorService.class));
        } else {
            ctx.startService(new Intent(ctx, SignMonitorService.class));
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, SignMonitorService.class));
    }
}
