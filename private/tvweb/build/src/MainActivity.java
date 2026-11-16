package org.tmplayer.tvweb;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

// Test-only Android TV wrapper for the web player's TV build, like the LG and Samsung ones: a
// full-screen WebView where the remote's keys go straight to the page. Back is handed to the page
// as a GoBack keydown; when the page does not take it (Home), the app closes.
public class MainActivity extends Activity {
    // Overridable at build time: build.sh writes the address here.
    static final String ADDRESS = "http://localhost:8092/tv/";

    private FrameLayout root;
    private WebView web;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;
    private boolean backPending;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WebView.setWebContentsDebuggingEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(12, 14, 16));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(12, 14, 16));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showUnreachable();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view;
                fullscreenDone = callback;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                view.requestFocus();
            }

            @Override
            public void onHideCustomView() {
                if (fullscreen == null) return;
                root.removeView(fullscreen);
                fullscreen = null;
                fullscreenDone = null;
                web.requestFocus();
            }
        });

        if (saved == null) web.loadUrl(ADDRESS);
        else web.restoreState(saved);
        web.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && !backPending) {
            backPending = true;
            // The page's own Back (webapp/src/tv/focus.ts and the player's keys) calls preventDefault
            // when it handled the key; the capture listener is on window, so any target reaches it.
            String js = "(function(){var t=document.activeElement||document.body;"
                + "var o={key:'GoBack',code:'',keyCode:166,which:166,bubbles:true,cancelable:true};"
                + "var d=new KeyboardEvent('keydown',o);"
                + "try{Object.defineProperty(d,'keyCode',{get:function(){return 166}});Object.defineProperty(d,'which',{get:function(){return 166}});}catch(x){}"
                + "var taken=!t.dispatchEvent(d);t.dispatchEvent(new KeyboardEvent('keyup',o));return taken;})()";
            web.evaluateJavascript(js, result -> {
                backPending = false;
                if ("true".equals(result)) return;
                if (fullscreen != null && fullscreenDone != null) { fullscreenDone.onCustomViewHidden(); return; }
                finish();
            });
        }
        return true;
    }

    private void showUnreachable() {
        String html = "<!DOCTYPE html><html><head><meta name=viewport content='width=device-width'>"
            + "<style>body{margin:0;background:#0c0e10;color:#f2f4f5;font-family:sans-serif;display:flex;"
            + "flex-direction:column;align-items:center;justify-content:center;height:100vh;text-align:center}"
            + "h1{font-size:40px;margin:0 0 20px}p{font-size:24px;color:#c4c9ce;margin:0 0 40px}"
            + "button{font-size:24px;font-weight:600;padding:14px 48px;border-radius:12px;border:4px solid transparent;"
            + "background:#f2f4f5;color:#0c0e10}button:focus{outline:none;border-color:#6ea8ff}</style></head><body>"
            + "<h1>Cannot reach TMPlayer</h1><p>Check the TV's internet connection.</p>"
            + "<button id=r autofocus onclick=\"location.replace('" + ADDRESS + "')\">Try again</button>"
            + "<script>document.getElementById('r').focus()</script></body></html>";
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
