package tf.monochrome.app;

import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
    @Override
    public void load() {
        super.load();

        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                webView.evaluateJavascript(
                    "if('serviceWorker' in navigator){" +
                    "navigator.serviceWorker.getRegistrations().then(function(r){r.forEach(function(reg){reg.unregister();});});" +
                    "navigator.serviceWorker.register=function(){return Promise.reject(new Error('SW disabled in native app'));};" +
                    "}" +
                    "if('caches' in window){caches.keys().then(function(k){k.forEach(function(key){caches.delete(key);});});}",
                    null
                );
            }

            @Override
            public void onPageLoaded(WebView webView) {
                webView.evaluateJavascript(
                    "if('serviceWorker' in navigator){" +
                    "navigator.serviceWorker.getRegistrations().then(function(r){r.forEach(function(reg){reg.unregister();});});" +
                    "}" +
                    "if('caches' in window){caches.keys().then(function(k){k.forEach(function(key){caches.delete(key);});});}",
                    null
                );
            }
        });
    }
}
