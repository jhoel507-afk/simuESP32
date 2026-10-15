package com.simuesp.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.*;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private static final int REQ_SAVE = 4021;
    private static final int REQ_OPEN = 4022;
    private byte[] pendingBytes;
    private ValueCallback<Uri[]> fileCallback;

    /** Puente JS -> Android para guardar archivos (el WebView no soporta descargas blob:). */
    public class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                pendingBytes = Base64.decode(base64, Base64.DEFAULT);
                final String n = name;
                final String m = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(m);
                    i.putExtra(Intent.EXTRA_TITLE, n);
                    startActivityForResult(i, REQ_SAVE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "No se pudo guardar el archivo", Toast.LENGTH_LONG).show());
            }
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFF0B1120);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        // Android 15+ dibuja edge-to-edge: separar el contenido de barras del sistema y teclado.
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; bt = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); bt = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, bt);
            return insets;
        });
        // Con targetSdk 36 ya no se llama onBackPressed(): usar el callback de retroceso predictivo.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                String sc = u.getScheme();
                if ("https".equals(sc) || "http".equals(sc)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                }
                return true; // nunca navegar dentro del WebView a contenido externo
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm())
                    .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm())
                    .setNegativeButton("Cancelar", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) {
                EditText et = new EditText(MainActivity.this);
                et.setText(def);
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setView(et)
                    .setPositiveButton("OK", (d, w) -> r.confirm(et.getText().toString()))
                    .setNegativeButton("Cancelar", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            // Necesario para "Importar proyecto" (<input type="file">)
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegir proyecto"), REQ_OPEN);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
        // Siempre arranca limpio (la propia página se inicia sin proyecto).
        if (b == null) web.loadUrl("file:///android_asset/index.html");
        else web.restoreState(b);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingBytes != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    os.write(pendingBytes);
                    os.flush();
                    Toast.makeText(this, "Archivo guardado", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingBytes = null;
        } else if (req == REQ_OPEN && fileCallback != null) {
            Uri[] result = null;
            if (res == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }

    private void handleBack() {
        if (web.canGoBack()) web.goBack(); else finish();
    }

    @Override
    public void onBackPressed() { // Android < 13
        handleBack();
    }
}
