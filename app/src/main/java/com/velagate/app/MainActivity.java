package com.velagate.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_CONF = 7019;
    private static final int MAX_CONF_BYTES = 2 * 1024 * 1024;
    private static final String FORMAT = "VELAGATE-CONF-1";
    private static final String PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAulZkt19w71eOcapPg++Y9TSToPrL3PyfbIg3DtwVhO71gle6ZCbJ0uMFHfZ7lRUXEGVaXNYyw7JhxSf/qV0FSnfXvipz5vv9jBLzQs86c6/NucPG+1OLH/DatZBY6ancwwYkZIk5gVLnY2hwa+8Cl62knikFWfcu6KDU653Yah1GlayuiwUYS5Kt4IS4qVntWgUcU5rduOuVmasoQjZgoHACq9l5W6bgJA2m6CH0GtFdYh6RKenQJpeVN40WlMvLFwCBTa5DaTv7MXpLAAyNxxj3cEjF6ctiCbr0Zwd+82FKaAal7sh48Hw+CI+ueluTy375nZKkCIYBx6LMsO+VMwIDAQAB";

    private WebView webView;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(17, 25, 31));
        getWindow().setNavigationBarColor(Color.rgb(17, 25, 31));
        prefs = getSharedPreferences("velagate_bindings", MODE_PRIVATE);

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
                sendError("读取文件失败：" + safeMessage(e));
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
        return name == null ? "unknown.conf" : name;
    }

    private String readText(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        try (InputStream in = resolver.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("无法打开文件");
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_CONF_BYTES) throw new IllegalArgumentException("配置文件过大");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void processConf(String fileName, String content) {
        try {
            if (fileName == null || !fileName.toLowerCase().endsWith(".conf")) {
                throw new IllegalArgumentException("只支持 .conf 文件");
            }
            JSONObject raw = new JSONObject(content);
            String format = raw.optString("format");
            String kind = raw.optString("kind");
            String fileId = raw.optString("fileId");
            String issuedAt = raw.optString("issuedAt");
            String payloadB64 = raw.optString("payload");
            String signatureB64 = raw.optString("signature");

            if (!FORMAT.equals(format)) throw new SecurityException("不是 VelaGate 配置文件");
            if (!("route".equals(kind) || "traffic".equals(kind))) throw new SecurityException("配置类型无效");
            if (fileId.isEmpty() || issuedAt.isEmpty() || payloadB64.isEmpty() || signatureB64.isEmpty()) {
                throw new SecurityException("配置字段不完整");
            }

            String signed = format + "\n" + kind + "\n" + fileId + "\n" + issuedAt + "\n" + payloadB64;
            if (!verifySignature(signed, signatureB64)) throw new SecurityException("签名校验失败，只能解析受信任配置");

            byte[] payloadBytes = Base64.decode(payloadB64, Base64.DEFAULT);
            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            if ("route".equals(kind) && !"EUROPE".equals(payload.optString("region"))) {
                throw new SecurityException("仅允许欧洲专线配置");
            }
            if ("traffic".equals(kind) && payload.optString("routeFileId").isEmpty()) {
                throw new SecurityException("流量包未绑定欧洲专线文件");
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

    private String deviceId() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest((androidId + "|com.velagate.app|v1").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return androidId;
        }
    }

    private JSONObject activateLocal(String routeId, String trafficId) throws Exception {
        String device = deviceId();
        String pairId = routeId + "::" + trafficId;
        String routeDevice = prefs.getString("file:" + routeId, null);
        String trafficDevice = prefs.getString("file:" + trafficId, null);
        String routePair = prefs.getString("pair:" + routeId, null);
        String trafficPair = prefs.getString("pair:" + trafficId, null);

        if ((routeDevice != null && !routeDevice.equals(device)) || (trafficDevice != null && !trafficDevice.equals(device))) {
            return result(false, "local", "FILE_ALREADY_BOUND", "配置文件已绑定其他设备");
        }
        if ((routePair != null && !routePair.equals(pairId)) || (trafficPair != null && !trafficPair.equals(pairId))) {
            return result(false, "local", "PAIR_MISMATCH", "配置文件已用于其他配对");
        }
        prefs.edit()
                .putString("file:" + routeId, device)
                .putString("file:" + trafficId, device)
                .putString("pair:" + routeId, pairId)
                .putString("pair:" + trafficId, pairId)
                .apply();
        return result(true, "local", "OK", "本设备绑定成功");
    }

    private JSONObject activateRemote(String endpoint, String routeId, String trafficId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject body = new JSONObject();
        body.put("routeFileId", routeId);
        body.put("trafficFileId", trafficId);
        body.put("deviceId", deviceId());
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = "";
        if (stream != null) {
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                text = out.toString(StandardCharsets.UTF_8.name());
            }
        }
        JSONObject result;
        try { result = new JSONObject(text); }
        catch (Exception ignored) { result = new JSONObject(); }
        result.put("mode", "remote");
        if (!result.has("ok")) result.put("ok", code >= 200 && code < 300);
        if (!result.has("message")) result.put("message", result.optBoolean("ok") ? "设备绑定成功" : "设备绑定失败");
        return result;
    }

    private JSONObject result(boolean ok, String mode, String code, String message) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        o.put("mode", mode);
        o.put("code", code);
        o.put("message", message);
        return o;
    }

    private void sendActivationResult(JSONObject result) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onActivationResult(" + JSONObject.quote(result.toString()) + ");", null));
    }

    private void sendError(String message) {
        String m = (message == null || message.trim().isEmpty()) ? "配置文件解析失败" : message;
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
        ioExecutor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public class Bridge {
        private final Context context;
        Bridge(Context context) { this.context = context; }

        @JavascriptInterface
        public void pickConfigs() {
            runOnUiThread(MainActivity.this::openPicker);
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getDeviceId() {
            return deviceId();
        }

        @JavascriptInterface
        public String getActivationMode() {
            return BuildConfig.ACTIVATION_URL == null || BuildConfig.ACTIVATION_URL.trim().isEmpty() ? "local" : "remote";
        }

        @JavascriptInterface
        public void activatePair(String routeId, String trafficId) {
            ioExecutor.execute(() -> {
                try {
                    JSONObject result;
                    String url = BuildConfig.ACTIVATION_URL == null ? "" : BuildConfig.ACTIVATION_URL.trim();
                    if (url.isEmpty()) result = activateLocal(routeId, trafficId);
                    else result = activateRemote(url, routeId, trafficId);
                    sendActivationResult(result);
                } catch (Exception e) {
                    try { sendActivationResult(result(false, "remote", "ACTIVATION_ERROR", safeMessage(e))); }
                    catch (Exception ignored) { sendError("设备绑定失败"); }
                }
            });
        }
    }
}
