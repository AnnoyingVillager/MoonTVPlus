package com.moontvplus.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements RemoteCommandHandler {
    private static final String TAG = "MoonTVGecko";
    private static GeckoRuntime runtime;

    private GeckoSession session;
    private GeckoView geckoView;
    private boolean canGoBack = false;
    private LocalRemoteServer localRemoteServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        geckoView = new GeckoView(this);
        geckoView.setFocusable(true);
        geckoView.setFocusableInTouchMode(true);
        geckoView.requestFocus();
        setContentView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        if (runtime == null) {
            // GeckoView Builder 不公开任意 prefs 注入接口，但 configFilePath() 支持从 YAML
            // 文件读取 prefs/env/args（官方文档机制，见 mozilla automation.rst）。
            // 部分老电视 SoC（如海思 8H56）的 GPU 驱动与 Gecko WebRender 不兼容，
            // 表现为页面已绘制但屏幕全黑，这里强制软件 WebRender。
            // 注意：该机制要求 SDK_INT > 21（Android 5.0 的 API 21 会被静默忽略）。
            String configPath = null;
            try {
                File configFile = new File(getFilesDir(), "geckoview-config.yaml");
                String yaml = "prefs:\n"
                        + "  gfx.webrender.software: true\n"
                        + "  layers.acceleration.disabled: true\n"
                        + "  media.hardware-video-decoding.enabled: false\n"
                        + "env:\n"
                        + "  MOZ_DISABLE_GPU: '1'\n";
                FileOutputStream out = new FileOutputStream(configFile);
                out.write(yaml.getBytes(StandardCharsets.UTF_8));
                out.close();
                configPath = configFile.getAbsolutePath();
                Log.i(TAG, "GeckoView soft-render config: " + configPath);
            } catch (IOException error) {
                Log.w(TAG, "Failed to write GeckoView config file", error);
            }

            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(true)
                    .consoleOutput(true)
                    .debugLogging(true)
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL);
            if (configPath != null) {
                builder.configFilePath(configPath);
            }
            try {
                runtime = GeckoRuntime.create(this, builder.build());
            } catch (Throwable error) {
                Log.e(TAG, "GeckoRuntime.create failed", error);
                throw error;
            }
        }

        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBackValue) {
                canGoBack = canGoBackValue;
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                Log.i(TAG, "onPageStart: " + url);
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                Log.i(TAG, "onPageStop success=" + success);
            }

            @Override
            public void onProgressChange(GeckoSession session, int progress) {
                Log.d(TAG, "onProgressChange: " + progress);
            }
        });
        // FCP 日志用于区分黑屏性质：
        // - 有 onFirstContentfulPaint 但仍黑屏 => SurfaceView 合成/GPU 驱动问题
        // - 一直没有 onFirstContentfulPaint => 内容进程崩溃或页面未渲染
        // 注意：onTitle 在 GeckoView 126 的 ContentDelegate 中已移除，勿再加回
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFirstContentfulPaint(GeckoSession session) {
                Log.i(TAG, "onFirstContentfulPaint");
            }

            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                Log.i(TAG, "onFullScreen: " + fullScreen);
            }
        });
        session.open(runtime);
        geckoView.setSession(session);
        setupLocalRemoteServer();
        session.loadUri(withLocalRemoteHash(buildTvUrl(BuildConfig.BASE_URL)));
    }


    private void setupLocalRemoteServer() {
        localRemoteServer = new LocalRemoteServer(this);
        localRemoteServer.start();
    }

    private int keyCodeForRemoteKey(String key, String digit) {
        if ("up".equals(key)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("down".equals(key)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("left".equals(key)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("right".equals(key)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("ok".equals(key)) return KeyEvent.KEYCODE_DPAD_CENTER;
        if ("back".equals(key)) return KeyEvent.KEYCODE_BACK;
        if ("menu".equals(key)) return KeyEvent.KEYCODE_MENU;
        if ("home".equals(key)) return KeyEvent.KEYCODE_HOME;
        if ("playPause".equals(key)) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if ("pageUp".equals(key)) return KeyEvent.KEYCODE_PAGE_UP;
        if ("pageDown".equals(key)) return KeyEvent.KEYCODE_PAGE_DOWN;
        if ("digit".equals(key) && digit != null && digit.length() == 1 && digit.charAt(0) >= '0' && digit.charAt(0) <= '9') {
            return KeyEvent.KEYCODE_0 + (digit.charAt(0) - '0');
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    @Override
    public void onRemoteKey(String key, boolean repeat, String digit) {
        mainHandler.post(() -> dispatchLocalRemoteKey(key, repeat, digit));
    }

    @Override
    public void onRemoteText(String mode, String text) {
        mainHandler.post(() -> dispatchLocalRemoteText(mode, text));
    }

    private void dispatchLocalRemoteKey(String key, boolean repeat, String digit) {
        if (session == null || key == null) return;
        String script = "javascript:window.dispatchEvent(new CustomEvent('moontv:local-remote-key',{detail:{key:"
                + JSONObject.quote(key)
                + ",repeat:" + (repeat ? "true" : "false")
                + ",digit:" + JSONObject.quote(digit == null ? "" : digit)
                + "}}));void(0)";
        session.loadUri(script);
    }

    private void dispatchLocalRemoteText(String mode, String text) {
        if (session == null) return;
        String script = "javascript:window.dispatchEvent(new CustomEvent('moontv:local-remote-text',{detail:{mode:"
                + JSONObject.quote(mode == null ? "replace" : mode)
                + ",text:" + JSONObject.quote(text == null ? "" : text)
                + "}}));void(0)";
        session.loadUri(script);
    }

    private String withLocalRemoteHash(String url) {
        String remoteUrl = localRemoteServer == null ? null : localRemoteServer.getRemoteUrl();
        if (remoteUrl == null || remoteUrl.isEmpty()) return url;
        try {
            return url + "#localRemoteUrl=" + URLEncoder.encode(remoteUrl, "UTF-8");
        } catch (Exception ignored) {
            return url;
        }
    }

    private static String buildTvUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            url = "http://192.168.1.10:3000";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/tv")) {
            return url;
        }
        return url + "/tv";
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack) {
            session.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (localRemoteServer != null) {
            localRemoteServer.stop();
            localRemoteServer = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
