/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nya.miku.wishmaster.http.hashwall;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Pattern;

import cz.msebera.android.httpclient.HttpHost;
import cz.msebera.android.httpclient.client.HttpClient;
import cz.msebera.android.httpclient.conn.params.ConnRouteParams;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.common.MainApplication;
import nya.miku.wishmaster.http.client.ExtendedHttpClient;
import nya.miku.wishmaster.lib.WebViewProxy;
import nya.miku.wishmaster.ui.CompatibilityImpl;

public class HashwallChecker {
    private static final String TAG = "HashwallChallengeChecker";

    /** таймаут при анти-ддос проверке в милисекундах */
    public static final long TIMEOUT = 35 * 1000;

    private static final Pattern PATTERN_COOKIE_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private HashwallChecker() {}
    private static HashwallChecker instance;
    
    /** Получить объект-синглтон */
    public static synchronized HashwallChecker getInstance() {
        if (instance == null) instance = new HashwallChecker();
        return instance;
    }

    /** Возвращает false, если в данный момент (какая-либо) анти-ддос проверка уже выполняется */
    public boolean isAvaibleAntiDDOS() {
        return !(processing || InterceptingAntiDDOS.getInstance().isProcessing());
    }


    /**
     * Пройти анти-ддос проверку hashwall
     * @param exception Hashwall исключение
     * @param httpClient HTTP клиент
     * @param task отменяемая задача
     * @param activity активность, в контексте которого будет запущен WebView (webkit)
     * @return полученная cookie или null, если проверка не прошла по таймауту, или проверка уже проходит в другом потоке
     */
    public Cookie checkAntiDDOS(HashwallExceptionAntiDDOS exception, HttpClient httpClient, CancellableTask task, Activity activity) {
        HttpHost proxy = null;
        if (httpClient instanceof ExtendedHttpClient) {
            proxy = ((ExtendedHttpClient) httpClient).getProxy();
        } else if (httpClient != null) {
            try {
                proxy = ConnRouteParams.getDefaultProxy(httpClient.getParams());
            } catch (Exception e) { /*ignore*/ }
        }
        if (proxy != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            if (httpClient instanceof ExtendedHttpClient) {
                return InterceptingAntiDDOS.getInstance().check(exception, (ExtendedHttpClient) httpClient, task, activity);
            } else {
                throw new IllegalArgumentException(
                        "cannot run anti-DDOS checking with proxy settings; http client is not instance of ExtendedHttpClient");
            }
        } else {
            return checkAntiDDOS(exception, proxy, task, activity);
        }
    }

    private volatile boolean processing = false;
    private volatile boolean processing2 = false;
    private volatile Cookie currentCookie;
    private volatile WebView webView;
    private volatile Context webViewContext;
    private final Object lock = new Object();
    private Cookie checkAntiDDOS(final HashwallExceptionAntiDDOS exception, final HttpHost proxy, CancellableTask task, final Activity activity) {
        synchronized (lock) {
            if (processing) return null;
            processing = true;
        }
        processing2 = true;
        currentCookie = null;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieSyncManager.createInstance(activity);
            CookieManager.getInstance().removeAllCookie();
        } else {
            CompatibilityImpl.clearCookies(CookieManager.getInstance());
        }

        final ViewGroup layout = (ViewGroup) activity.getWindow().getDecorView().getRootView();
        final WebViewClient client = new WebViewClient() {
            @Override
            public void onPageFinished(WebView webView, String url) {
                super.onPageFinished(webView, url);
                Logger.d(TAG, "Got Page: "+url);

                String name = null, value = null;
                try {
                    String[] cookies = CookieManager.getInstance().getCookie(url).split(";");
                    for (String cookie : cookies) {
                        if (cookie != null) {
                            String[] cookiePair = cookie.split("=");
                            if (isHWCookie(cookiePair[1])) {
                                name = cookiePair[0];
                                value = cookiePair[1];
                            }
                        }
                    }
                } catch (NullPointerException e) {
                    Logger.e(TAG, e);
                }

                if (name != null && value != null) {
                    BasicClientCookie hwCookie = new BasicClientCookie(name, value);
                    hwCookie.setDomain("." + Uri.parse(url).getHost());
                    hwCookie.setComment("Hashwall");
                    hwCookie.setPath("/");
                    currentCookie = hwCookie;
                    Logger.d(TAG, "Cookie found: " + value);
                    processing2 = false;
                } else {
                    Logger.d(TAG, "Cookie is not found");
                }
            }
        };

        activity.runOnUiThread(new Runnable() {
            @SuppressLint("SetJavaScriptEnabled")
            @Override
            public void run() {
                webView = new WebView(activity);
                webView.setVisibility(View.GONE);
                layout.addView(webView);
                webView.setWebViewClient(client);
                webView.getSettings().setUserAgentString(MainApplication.getInstance().settings.getUserAgentString());
                webView.getSettings().setJavaScriptEnabled(true);
                webViewContext = webView.getContext();
                if (proxy != null) WebViewProxy.setProxy(webViewContext, proxy.getHostName(), proxy.getPort());
                webView.loadUrl(exception.getUrl());
            }
        });

        long startTime = System.currentTimeMillis();
        while (processing2) {
            long time = System.currentTimeMillis() - startTime;
            if ((task != null && task.isCancelled()) || time > TIMEOUT) {
                processing2 = false;
            }
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    layout.removeView(webView);
                    webView.stopLoading();
                    webView.clearCache(true);
                    webView.destroy();
                    webView = null;
                } finally {
                    if (proxy != null) WebViewProxy.setProxy(webViewContext, null, 0);
                    processing = false;
                }
            }
        });

        return currentCookie;
    }

    static boolean isHWCookie(Cookie cookie, String url) {
        try {
            String cookieDomain = cookie.getDomain();
            if (!cookieDomain.startsWith(".")) {
                cookieDomain = "." + cookieDomain;
            }
            String cookieUrl = "." + Uri.parse(url).getHost();
            if (cookieUrl.endsWith(cookieDomain)) {
                return PATTERN_COOKIE_FORMAT.matcher(cookie.getValue()).matches();
            }
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
        return false;
    }

    static boolean isHWCookie(String cookieValue) {
        return PATTERN_COOKIE_FORMAT.matcher(cookieValue).matches();
    }
}
