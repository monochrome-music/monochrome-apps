package tf.monochrome.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.DownloadListener;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

public class MainActivity extends BridgeActivity {

    private static final String tag = "MonoDL";
    private static final int req_storage = 1001;

    private volatile Pending pending;

    private static final String hook_js =
        "(function(){" +
        "if(window.__monoDlHook)return;" +
        "window.__monoDlHook=true;" +
        "var o=URL.revokeObjectURL.bind(URL);" +
        "URL.revokeObjectURL=function(u){" +
        "if(u&&u.indexOf('blob:')===0){setTimeout(function(){o(u);},10000);}" +
        "else{o(u);}" +
        "};" +
        "document.addEventListener('click',function(e){" +
        "var n=e.target;" +
        "while(n&&n.nodeType===1&&n.tagName!=='A')n=n.parentNode;" +
        "if(!n||n.tagName!=='A'||!n.hasAttribute('download'))return;" +
        "var h=n.href||'';" +
        "if(h.indexOf('blob:')!==0)return;" +
        "if(!window.AndroidDownload)return;" +
        "e.preventDefault();e.stopPropagation();" +
        "var fn=n.getAttribute('download')||'download';" +
        "fetch(h).then(function(r){return r.blob();}).then(function(b){" +
        "var rd=new FileReader();" +
        "rd.onloadend=function(){" +
        "var s=rd.result||'';" +
        "var c=s.indexOf(',');" +
        "var d=c>=0?s.substring(c+1):s;" +
        "var m=b.type||'application/octet-stream';" +
        "window.AndroidDownload.saveDownload(d,fn,m);" +
        "};" +
        "rd.readAsDataURL(b);" +
        "}).catch(function(err){" +
        "console.error('DL hook error:',err);" +
        "if(window.AndroidDownload&&window.AndroidDownload.showToast){" +
        "window.AndroidDownload.showToast('Download failed: '+err.message);" +
        "}" +
        "});" +
        "},true);" +
        "})();";

    @Override
    public void load() {
        super.load();

        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView wv) {
                wv.evaluateJavascript(hook_js, null);
                wv.evaluateJavascript(
                    "if('serviceWorker' in navigator){" +
                    "navigator.serviceWorker.getRegistrations().then(function(r){r.forEach(function(reg){reg.unregister();});});" +
                    "navigator.serviceWorker.register=function(){return Promise.reject(new Error('SW disabled in native app'));};" +
                    "}" +
                    "if('caches' in window){caches.keys().then(function(k){k.forEach(function(key){caches.delete(key);});});}",
                    null
                );
            }

            @Override
            public void onPageLoaded(WebView wv) {
                wv.evaluateJavascript(hook_js, null);
                wv.evaluateJavascript(
                    "if('serviceWorker' in navigator){" +
                    "navigator.serviceWorker.getRegistrations().then(function(r){r.forEach(function(reg){reg.unregister();});});" +
                    "}" +
                    "if('caches' in window){caches.keys().then(function(k){k.forEach(function(key){caches.delete(key);});});}",
                    null
                );
            }
        });

        final WebView wv = bridge.getWebView();
        if (wv != null) {
            wv.addJavascriptInterface(new DLInterface(this), "AndroidDownload");

            wv.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String ua, String cd, String mt, long len) {
                    if (url.startsWith("blob:")) {
                        return;
                    }
                    doDownload(url, cd, mt);
                }
            });

            String ua = wv.getSettings().getUserAgentString();
            wv.getSettings().setUserAgentString(ua.replace("; wv", ""));

            final WebViewClient oc = wv.getWebViewClient();
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    Uri u = req.getUrl();
                    if (shouldOpenExternally(v, u)) {
                        return true;
                    }
                    return oc.shouldOverrideUrlLoading(v, req);
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    Uri u = Uri.parse(url);
                    if (shouldOpenExternally(v, u)) {
                        return true;
                    }
                    return oc.shouldOverrideUrlLoading(v, url);
                }

                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                    oc.onPageStarted(v, url, fav);
                }

                @Override
                public void onPageFinished(WebView v, String url) {
                    oc.onPageFinished(v, url);
                }

                @Override
                public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                    oc.onReceivedError(v, req, err);
                }

                @Override
                public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse resp) {
                    oc.onReceivedHttpError(v, req, resp);
                }

                private boolean shouldOpenExternally(WebView v, Uri u) {
                    String h = u.getHost();
                    if (h != null && isOAuthDomain(h) && !h.endsWith(".monochrome.tf") && !h.equals("monochrome.tf")) {
                        Intent i = new Intent(Intent.ACTION_VIEW, u);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        v.getContext().startActivity(i);
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    private static String cleanName(String n) {
        if (n == null || n.isEmpty()) return "download";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String nameFromCD(String cd) {
        if (cd == null) return null;
        int i = cd.indexOf("filename=");
        if (i < 0) return null;
        String n = cd.substring(i + 9).trim();
        if (n.startsWith("\"") && n.endsWith("\"")) {
            n = n.substring(1, n.length() - 1);
        }
        return n.isEmpty() ? null : n;
    }

    private void doDownload(String url, String cd, String mt) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mt != null && !mt.isEmpty()) {
                r.setMimeType(mt);
            }
            String n = nameFromCD(cd);
            if (n != null) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, n);
            }
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(r);
                Toast.makeText(this, "Downloading\u2026", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(tag, "DM request failed", e);
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
        }
    }

    private static void saveMS(Context ctx, byte[] d, String n, String mt) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, cleanName(n));
        v.put(MediaStore.Downloads.MIME_TYPE, mt != null ? mt : "application/octet-stream");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Monochrome");

        android.content.ContentResolver cr = ctx.getContentResolver();
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) {
            throw new IOException("MediaStore insert returned null");
        }
        OutputStream os = cr.openOutputStream(u);
        if (os == null) {
            throw new IOException("Unable to open output stream for " + u);
        }
        try {
            os.write(d);
            os.flush();
        } finally {
            os.close();
        }
    }

    @SuppressWarnings("deprecation")
    private static void saveLegacy(Context ctx, byte[] d, String n, String mt) throws IOException {
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Monochrome"
        );
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create dir: " + dir.getAbsolutePath());
        }

        String sn = cleanName(n);
        File f = new File(dir, sn);
        if (f.exists()) {
            int dot = sn.lastIndexOf('.');
            String base = dot > 0 ? sn.substring(0, dot) : sn;
            String ext = dot > 0 ? sn.substring(dot) : "";
            int c = 1;
            while (f.exists()) {
                f = new File(dir, base + " (" + c + ")" + ext);
                c++;
            }
        }

        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(d);
            fos.flush();
        } finally {
            fos.close();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == req_storage) {
            final Pending p = pending;
            pending = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                if (p != null) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                saveLegacy(MainActivity.this, p.data, p.name, p.mt);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, "Downloaded: " + p.name, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            } catch (final IOException e) {
                                Log.e(tag, "Legacy save failed", e);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }
                    }).start();
                }
            } else {
                Toast.makeText(this, "Storage permission denied \u2014 cannot save downloads.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static class Pending {
        final byte[] data;
        final String name;
        final String mt;

        Pending(byte[] data, String name, String mt) {
            this.data = data;
            this.name = name;
            this.mt = mt;
        }
    }

    private static class DLInterface {
        private final WeakReference<MainActivity> ref;

        DLInterface(MainActivity a) {
            this.ref = new WeakReference<MainActivity>(a);
        }

        @android.webkit.JavascriptInterface
        public void saveDownload(final String b64, final String name, final String mt) {
            final MainActivity a = ref.get();
            if (a == null) return;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final byte[] d = Base64.decode(b64, Base64.DEFAULT);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            saveMS(a, d, name, mt);
                            a.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(a, "Downloaded: " + name, Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            if (ContextCompat.checkSelfPermission(a, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    == PackageManager.PERMISSION_GRANTED) {
                                saveLegacy(a, d, name, mt);
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(a, "Downloaded: " + name, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            } else {
                                a.pending = new Pending(d, name, mt);
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        ActivityCompat.requestPermissions(
                                            a,
                                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                            req_storage
                                        );
                                    }
                                });
                            }
                        }
                    } catch (final Exception e) {
                        Log.e(tag, "saveDownload failed", e);
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(a, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }).start();
        }

        @android.webkit.JavascriptInterface
        public void showToast(final String msg) {
            final MainActivity a = ref.get();
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private static boolean isOAuthDomain(String h) {
        return h.endsWith(".monochrome.tf")
            || h.equals("monochrome.tf")
            || h.equals("accounts.google.com")
            || h.endsWith(".google.com")
            || h.equals("discord.com")
            || h.endsWith(".discord.com")
            || h.equals("github.com")
            || h.endsWith(".github.com")
            || h.equals("auth.monochrome.tf");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && "monochrome.tf".equals(data.getHost())) {
                String url = data.toString();
                try {
                    com.getcapacitor.Bridge b = bridge;
                    if (b != null && b.getWebView() != null) {
                        b.getWebView().post(() -> {
                            b.getWebView().evaluateJavascript(
                                "window.location.href = " + jsonQuote(url) + ";",
                                null
                            );
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
