package com.velagate.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.DragEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int PICK_CONF = 7019;
    private static final int MAX_CONF_BYTES = 2 * 1024 * 1024;
    private static final String FORMAT = "VELAGATE-CONF-1";
    private static final String PROBE_URL = "https://www.baidu.com/";
    private static final String PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAulZkt19w71eOcapPg++Y9TSToPrL3PyfbIg3DtwVhO71gle6ZCbJ0uMFHfZ7lRUXEGVaXNYyw7JhxSf/qV0FSnfXvipz5vv9jBLzQs86c6/NucPG+1OLH/DatZBY6ancwwYkZIk5gVLnY2hwa+8Cl62knikFWfcu6KDU653Yah1GlayuiwUYS5Kt4IS4qVntWgUcU5rduOuVmasoQjZgoHACq9l5W6bgJA2m6CH0GtFdYh6RKenQJpeVN40WlMvLFwCBTa5DaTv7MXpLAAyNxxj3cEjF6ctiCbr0Zwd+82FKaAal7sh48Hw+CI+ueluTy375nZKkCIYBx6LMsO+VMwIDAQAB";

    private WebView webView;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService trafficExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> trafficTask;
    private long lastRxBytes = -1L;
    private long lastTxBytes = -1L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(17, 25, 31));
        getWindow().setNavigationBarColor(Color.rgb(17, 25, 31));

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);

        WebView.setWebContentsDebuggingEnabled(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(this), "Android");
        webView.setOnDragListener(this::handleDrag);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean handleDrag(View view, DragEvent event) {
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
            return event.getClipDescription() != null;
        }
        if (event.getAction() == DragEvent.ACTION_DROP) {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try { requestDragAndDropPermissions(event); } catch (Exception ignored) {}
            }
            ClipData data = event.getClipData();
            if (data == null || data.getItemCount() == 0) return false;
            for (int i = 0; i < data.getItemCount(); i++) {
                Uri uri = data.getItemAt(i).getUri();
                if (uri != null) processUriAsync(uri);
            }
            return true;
        }
        return true;
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_CONF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_CONF || resultCode != RESULT_OK || data == null) return;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) processUriAsync(uri);
            }
        } else if (data.getData() != null) {
            processUriAsync(data.getData());
        }
    }

    private void processUriAsync(Uri uri) {
        ioExecutor.execute(() -> {
            try {
                String name = getDisplayName(uri);
                String content = readText(uri);
                processConf(name, content);
            } catch (Exception e) {
                sendError("Unable to read configuration: " + safeMessage(e));
            }
        });
    }

    private String getDisplayName(Uri uri) {
        String name = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return name == null ? "configuration.conf" : name;
    }

    private String readText(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        try (InputStream in = resolver.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("File cannot be opened");
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_CONF_BYTES) throw new IllegalArgumentException("Configuration file is too large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void processConf(String fileName, String content) {
        try {
            if (fileName == null || !fileName.toLowerCase().endsWith(".conf")) {
                throw new IllegalArgumentException("Only .conf files are supported");
            }

            JSONObject raw = new JSONObject(content);
            String format = raw.optString("format");
            String kind = raw.optString("kind");
            String fileId = raw.optString("fileId");
            String issuedAt = raw.optString("issuedAt");
            String payloadB64 = raw.optString("payload");
            String signatureB64 = raw.optString("signature");

            if (!FORMAT.equals(format)) throw new SecurityException("Unsupported VelaGate configuration");
            if (!("route".equals(kind) || "traffic".equals(kind))) throw new SecurityException("Invalid configuration type");
            if (fileId.isEmpty() || issuedAt.isEmpty() || payloadB64.isEmpty() || signatureB64.isEmpty()) {
                throw new SecurityException("Incomplete configuration file");
            }

            String signed = format + "\n" + kind + "\n" + fileId + "\n" + issuedAt + "\n" + payloadB64;
            if (!verifySignature(signed, signatureB64)) {
                throw new SecurityException("Signature verification failed");
            }

            byte[] payloadBytes = Base64.decode(payloadB64, Base64.DEFAULT);
            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            if ("route".equals(kind) && !"EUROPE".equals(payload.optString("region"))) {
                throw new SecurityException("Only the Europe route configuration is supported");
            }
            if ("traffic".equals(kind) && payload.optString("routeFileId").isEmpty()) {
                throw new SecurityException("Traffic configuration is not bound to a route file");
            }

            JSONObject normalized = new JSONObject();
            normalized.put("kind", kind);
            normalized.put("fileId", fileId);
            normalized.put("issuedAt", issuedAt);
            normalized.put("payload", payload);

            runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onNativeConf(" + JSONObject.quote(normalized.toString()) + "," + JSONObject.quote(fileName) + ");", null));
        } catch (Exception e) {
            sendError(safeMessage(e));
        }
    }

    private boolean verifySignature(String signed, String signatureB64) throws Exception {
        byte[] der = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(signed.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.decode(signatureB64, Base64.DEFAULT));
    }

    private synchronized void startTrafficProbe() {
        stopTrafficProbeLocked();
        int uid = android.os.Process.myUid();
        lastRxBytes = TrafficStats.getUidRxBytes(uid);
        lastTxBytes = TrafficStats.getUidTxBytes(uid);
        trafficTask = trafficExecutor.scheduleAtFixedRate(this::runTrafficProbe, 0, 5, TimeUnit.SECONDS);
    }

    private synchronized void stopTrafficProbe() {
        stopTrafficProbeLocked();
    }

    private void stopTrafficProbeLocked() {
        if (trafficTask != null) {
            trafficTask.cancel(true);
            trafficTask = null;
        }
        lastRxBytes = -1L;
        lastTxBytes = -1L;
    }

    private void runTrafficProbe() {
        long started = SystemClock.elapsedRealtime();
        boolean ok = false;
        int fallbackRx = 0;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(PROBE_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "VelaGate/1.1 Android");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1 && fallbackRx < 65536) {
                        fallbackRx += n;
                    }
                }
            }
            ok = code >= 200 && code < 400;
        } catch (Exception ignored) {
            ok = false;
        } finally {
            if (conn != null) conn.disconnect();
        }

        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - started);
        int uid = android.os.Process.myUid();
        long nowRx = TrafficStats.getUidRxBytes(uid);
        long nowTx = TrafficStats.getUidTxBytes(uid);
        long rxDelta;
        long txDelta;

        synchronized (this) {
            if (trafficTask == null) return;
            if (nowRx == TrafficStats.UNSUPPORTED || lastRxBytes == TrafficStats.UNSUPPORTED || lastRxBytes < 0) {
                rxDelta = Math.max(0, fallbackRx);
            } else {
                rxDelta = Math.max(0L, nowRx - lastRxBytes);
            }
            if (nowTx == TrafficStats.UNSUPPORTED || lastTxBytes == TrafficStats.UNSUPPORTED || lastTxBytes < 0) {
                txDelta = 0L;
            } else {
                txDelta = Math.max(0L, nowTx - lastTxBytes);
            }
            lastRxBytes = nowRx;
            lastTxBytes = nowTx;
        }

        sendTrafficSample(rxDelta, txDelta, ok, elapsed);
    }

    private void sendTrafficSample(long rxBytes, long txBytes, boolean ok, long elapsedMs) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "window.onTrafficSample(" + rxBytes + "," + txBytes + "," + ok + "," + elapsedMs + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void sendError(String message) {
        String m = (message == null || message.trim().isEmpty()) ? "Configuration parsing failed" : message;
        runOnUiThread(() -> {
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
            webView.evaluateJavascript("window.onNativeError(" + JSONObject.quote(m) + ");", null);
        });
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    @Override
    protected void onDestroy() {
        stopTrafficProbe();
        ioExecutor.shutdownNow();
        trafficExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public class Bridge {
        private final Context context;

        Bridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void pickConfigs() {
            runOnUiThread(MainActivity.this::openPicker);
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void startTrafficProbe() {
            MainActivity.this.startTrafficProbe();
        }

        @JavascriptInterface
        public void stopTrafficProbe() {
            MainActivity.this.stopTrafficProbe();
        }
    }
}
