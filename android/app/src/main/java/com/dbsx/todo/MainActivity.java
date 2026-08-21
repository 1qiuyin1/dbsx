package com.dbsx.todo;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

// dbsx 待办 - WebView 壳
// 职责: 加载网页版 + 数据桥(内部存储) + 通知权限 + 定时检查
public class MainActivity extends Activity {

    public static final String CHANNEL_ID = "dbsx";

    private WebView web;
    private ValueCallback<Uri[]> uploadMessage;
    private static final int FILE_CHOOSER = 1001;
    private static final int REQ_NOTIF = 1002;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // 数据持久化
        web.setWebViewClient(new WebViewClient());

        // 导入功能：打开系统文件选择器
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (uploadMessage != null) uploadMessage.onReceiveValue(null);
                uploadMessage = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择文件"), FILE_CHOOSER);
                return true;
            }
        });

        // 数据桥：网页每次保存时同步到内部文件，原生通知据此提醒
        web.addJavascriptInterface(new JsBridge(), "Android");

        web.loadUrl("file:///android_asset/dbsx_mobile.html");
        setContentView(web);

        setupNotifications();
        TodoChecker.schedule(this); // 安排后台定时检查
        TodoChecker.check(this);    // 打开应用时立即检查一次
    }

    // 通知渠道 + 运行时权限（Android 13+ 需要）
    private void setupNotifications() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
        }
    }

    // 网页 <-> 内部存储 数据桥
    private class JsBridge {
        private File dataFile() {
            return new File(getFilesDir(), "todos.json");
        }

        @JavascriptInterface
        public void saveData(String json) {
            try {
                FileOutputStream fos = new FileOutputStream(dataFile());
                fos.write(json.getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) { /* 忽略写入失败 */ }
        }

        @JavascriptInterface
        public String loadData() {
            try {
                File f = dataFile();
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_CHOOSER) {
            if (uploadMessage != null) {
                uploadMessage.onReceiveValue(res == RESULT_OK ? WebChromeClient.FileChooserParams.parseResult(res, data) : null);
                uploadMessage = null;
            }
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
