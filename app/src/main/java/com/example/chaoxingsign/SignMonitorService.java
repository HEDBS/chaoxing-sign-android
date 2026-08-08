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
 * 监听服务(前台): 每 60 秒轮询全部课程, 检测到签到按模式处理
 *  - 确认模式(默认): 通知提醒 -> 用户点"确认签到"才签
 *  - 延时模式(开关): 检测到后等 N 秒自动签(无需确认)
 *  - 保险(开关, 仅确认模式): 通知后超时未确认自动签兜底
 *  - 手势/签到码/位置/二维码: 通知用户点开签到页手动签(带 activeId 直选)
 */
public class SignMonitorService extends Service {

    public static final String ACTION_STOP = "com.example.chaoxingsign.STOP";
    public static final String ACTION_CONFIRM = "com.example.chaoxingsign.CONFIRM";
    private static final String CHANNEL_ID = "monitor_channel";
    private static final int NOTIF_MONITOR = 1;
    private static final int NOTIF_ALERT = 2;
    private static final long INTERVAL = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private ChaoxingApi api;
    private List<ChaoxingApi.Course> courses;
    private final Set<String> handled = new HashSet<>();
    private final Set<String> confirmed = new HashSet<>();

    private boolean delayEnabled, insuranceEnabled;
    private int delaySeconds, insuranceSeconds;

    private final Thread monitorThread = new Thread(() -> {
        while (running) {
            try { scan(); } catch (Exception e) {
                android.util.Log.e("SignMonitor", "轮询异常: " + e.getMessage());
            }
            try { Thread.sleep(INTERVAL); } catch (InterruptedException e) { break; }
        }
    }, "SignMonitor-Thread");

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        loadSettings();
        startForeground(NOTIF_MONITOR, buildMonitorNotification());

        api = ChaoxingApi.instance;
        if (api == null) { ChaoxingApi.loadSession(this); api = ChaoxingApi.instance; }
        if (api == null) {
            android.util.Log.e("SignMonitor", "无登录态, 服务停止");
            stopSelf();
            return;
        }
        new Thread(() -> {
            try {
                courses = api.getCourses();
                android.util.Log.d("SignMonitor", "课程加载完成: " + (courses == null ? 0 : courses.size()));
            } catch (Exception e) {
                android.util.Log.e("SignMonitor", "课程加载失败: " + e.getMessage());
            }
            if (courses != null) { running = true; monitorThread.start(); }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CONFIRM.equals(intent.getAction())) {
            ChaoxingApi.SignActivity act = (ChaoxingApi.SignActivity) intent.getSerializableExtra("act");
            if (act != null) { confirmed.add(act.activeId); autoSign(act); }
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
    public IBinder onBind(Intent intent) { return null; }

    // ============ 轮询 ============
    private void scan() {
        if (api == null || courses == null) return;
        android.util.Log.d("SignMonitor", "轮询 " + courses.size() + " 门课");
        int ok = 0, fail = 0;
        for (ChaoxingApi.Course c : courses) {
            try {
                java.util.List<ChaoxingApi.SignActivity> acts =
                        api.getActiveActivities(c.courseId, c.classId, c.courseName);
                ok++;
                if (acts != null) {
                    for (ChaoxingApi.SignActivity act : acts) {
                        if (act.signed || handled.contains(act.activeId)) continue;
                        handled.add(act.activeId);
                        android.util.Log.d("SignMonitor", act.courseName
                                + " [" + ChaoxingApi.typeName(act.otherId) + "] " + act.name);
                        handleActivity(act);
                    }
                }
            } catch (Exception e) {
                fail++;
                if (fail <= 3) android.util.Log.e("SignMonitor", "[" + c.courseName + "]失败: " + e.getMessage());
            }
        }
        android.util.Log.d("SignMonitor", "本轮完成 ok=" + ok + " fail=" + fail);
    }

    private void handleActivity(ChaoxingApi.SignActivity act) {
        if (act.otherId == 0) {
            if (delayEnabled) {
                String title = cn(act) + " 检测到签到: " + act.name;
                notifyAlert(act, title, delaySeconds + " 秒后自动签到", true);
                handler.postDelayed(() -> autoSign(act), delaySeconds * 1000L);
            } else {
                notifyConfirm(act);
                if (insuranceEnabled) {
                    handler.postDelayed(() -> {
                        if (!confirmed.contains(act.activeId)) {
                            android.util.Log.d("SignMonitor", "保险触发: " + act.courseName);
                            autoSign(act);
                        }
                    }, insuranceSeconds * 1000L);
                }
            }
        } else {
            notifyManual(act);
        }
    }

    private void autoSign(ChaoxingApi.SignActivity act) {
        new Thread(() -> {
            String msg;
            try {
                api.preSign(act);
                if (api.isPhotoSign(act.activeId)) {
                    String oid = api.getObjectId();
                    msg = oid == null ? "云盘未找到 0.jpg/0.png" : api.signPhoto(act, oid);
                } else {
                    msg = api.signGeneral(act);
                }
            } catch (Exception e) { msg = "出错: " + e.getMessage(); }
            notifyResult(act, msg);
        }).start();
    }

    // ============ 通知 ============
    private void createChannel() {
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

    /** 确认模式: 通知带课程名+类型, 点通知直进签到页(带 activeId) */
    private void notifyConfirm(ChaoxingApi.SignActivity act) {
        Intent open = buildSignIntent(act);
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(),
                open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent confirm = new Intent(this, SignMonitorService.class);
        confirm.setAction(ACTION_CONFIRM);
        confirm.putExtra("act", act);
        PendingIntent cpi = PendingIntent.getService(this, (int) System.currentTimeMillis(),
                confirm, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = cn(act) + " [" + ChaoxingApi.typeName(act.otherId) + "] " + act.name;
        String text = insuranceEnabled
                ? "点击确认签到 · " + insuranceSeconds + " 秒未确认将自动签"
                : "点击确认签到";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .addAction(0, "确认签到", cpi);
        getSystemService(NotificationManager.class).notify(NOTIF_ALERT, b.build());
    }

    /** 延时/手动签到通知: 带课程名, activeId 直选 */
    private void notifyAlert(ChaoxingApi.SignActivity act, String title, String text, boolean withAction) {
        Intent open = buildSignIntent(act);
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

    /** 需手动签到: 课程名+类型+点通知直进签到页 */
    private void notifyManual(ChaoxingApi.SignActivity act) {
        String type = ChaoxingApi.typeName(act.otherId);
        String title = cn(act) + " 检测到" + type + "签到";
        notifyAlert(act, title, "点击前往手动签到", false);
    }

    private void notifyResult(ChaoxingApi.SignActivity act, String msg) {
        boolean ok = "success".equals(msg.trim());
        String prefix = cn(act);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(ok ? "✅ 签到成功" : "⚠️ 签到失败")
                .setContentText(prefix + act.name + ": " + (ok ? "已自动签到" : msg))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_ALERT, n);
    }

    /** 构建带 activeId 直选的 SignActivity Intent */
    private Intent buildSignIntent(ChaoxingApi.SignActivity act) {
        Intent i = new Intent(this, SignActivity.class);
        i.putExtra("courseId", act.courseId);
        i.putExtra("classId", act.classId);
        i.putExtra("courseName", act.courseName != null ? act.courseName : act.name);
        i.putExtra("activeId", act.activeId);
        i.putExtra("otherId", act.otherId);
        i.putExtra("actName", act.name);
        return i;
    }

    /** 「课程名」前缀(用于通知标题) */
    private static String cn(ChaoxingApi.SignActivity act) {
        return "\u300c" + (act.courseName != null ? act.courseName : "") + "\u300d";
    }

    // ============ 工具 ============
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        delayEnabled = sp.getBoolean(SettingsActivity.KEY_DELAY_ENABLED, false);
        delaySeconds = sp.getInt(SettingsActivity.KEY_DELAY_SECONDS, 30);
        insuranceEnabled = sp.getBoolean(SettingsActivity.KEY_INSURANCE_ENABLED, false);
        insuranceSeconds = sp.getInt(SettingsActivity.KEY_INSURANCE_SECONDS, 60);
    }

    public static void start(Context ctx) {
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
