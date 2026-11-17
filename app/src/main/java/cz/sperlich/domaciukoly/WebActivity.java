package cz.sperlich.domaciukoly;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Náhled originální stránky třídy přímo v aplikaci. */
public class WebActivity extends Activity {
    WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#F6F3EE"));
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F3EE"));

        TextView bar = new TextView(this);
        bar.setText("←  Originál zadání");
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bar.setTypeface(Typeface.DEFAULT_BOLD);
        bar.setTextColor(Color.parseColor("#23201C"));
        int p = (int) (14 * getResources().getDisplayMetrics().density);
        bar.setPadding(p, p, p, p);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOnClickListener(v -> finish());
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        String url = getIntent().getStringExtra("url");
        web.loadUrl(url != null ? url : Store.url(this));
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
