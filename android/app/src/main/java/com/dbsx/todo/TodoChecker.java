package com.dbsx.todo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 后台定时检查待办：截止前 24 小时（或已逾期）推送系统通知，每天每项最多一次
// 触发方式: 周期闹钟(15分钟) + 开机广播
public class TodoChecker extends BroadcastReceiver {

    public static final String ACTION_CHECK = "com.dbsx.todo.CHECK";
    public static final long INTERVAL_MS = 15 * 60 * 1000L;  // 检查间隔 15 分钟
    public static final long WINDOW_MS = 24 * 3600 * 1000L;  // 提前 24 小时提醒
    private static final String PREFS = "dbsx_notified";
    private static final String CHANNEL_ID = "dbsx";

    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            schedule(c); // 手机重启后重新安排定时检查
            return;
        }
        check(c);
    }

    // 安排周期检查（应用启动时和开机时调用）
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, TodoChecker.class).setAction(ACTION_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS, INTERVAL_MS, pi);
    }

    // 读取待办，通知已逾期或 24 小时内到期的项目
    public static void check(Context c) {
        String json = readData(c);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            long now = System.currentTimeMillis();
            SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name");
                String deadline = o.optString("deadline");
                long due = parseDeadline(deadline);
                if (name.length() == 0 || due <= 0) continue;
                boolean urgent = due <= now || due - now <= WINDOW_MS; // 已逾期或 24 小时内
                if (!urgent) continue;
                String key = name + "|" + deadline;
                if (today.equals(prefs.getString(key, ""))) continue; // 今天已提醒过
                prefs.edit().putString(key, today).commit();
                notify(c, nm, name, deadline, o.optString("description"));
            }
        } catch (Exception e) { /* 数据损坏时静默跳过 */ }
    }

    // 解析 "yyyy-MM-dd" 或 "yyyy-MM-dd HH:mm"
    private static long parseDeadline(String s) {
        if (s == null || s.length() == 0) return 0;
        try {
            if (s.contains(" ")) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s).getTime();
            }
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void notify(Context c, NotificationManager nm, String name, String deadline, String desc) {
        String body = "截止 " + deadline + (desc.length() > 0 ? " · " + desc : "");
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "待办提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
            n = new Notification.Builder(c, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("⏰ 待办：" + name)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();
        } else {
            n = new Notification.Builder(c)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("⏰ 待办：" + name)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();
        }
        try { nm.notify(name.hashCode() & 0x7fffffff, n); } catch (Exception e) { }
    }

    private static String readData(Context c) {
        try {
            File f = new File(c.getFilesDir(), "todos.json");
            if (!f.exists()) return null;
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) > 0) off += n;
            fis.close();
            return new String(buf, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
