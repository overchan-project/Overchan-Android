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

package nya.miku.wishmaster.chans.infinity;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceGroup;
import android.support.v4.content.res.ResourcesCompat;

import org.apache.commons.lang3.tuple.Pair;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.NameValuePair;
import cz.msebera.android.httpclient.client.entity.UrlEncodedFormEntity;
import cz.msebera.android.httpclient.entity.mime.content.ByteArrayBody;
import cz.msebera.android.httpclient.message.BasicHeader;
import cz.msebera.android.httpclient.message.BasicNameValuePair;

import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.api.models.DeletePostModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.LazyPreferences;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;
import nya.miku.wishmaster.lib.base64.Base64;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public class LolifoxModule extends InfinityModule {
    private static final String CHAN_NAME = "lolifox.club";
    private static final String DEFAULT_DOMAIN = "lolifox.club";
    private static final String ONION_DOMAIN = "kdrlbdzp2kzhofos5nfzd7ohz6f6n4fuwrkhs7adgx3l7rvhlqna3cad.onion";
    private static final String[] DOMAINS = new String[]{DEFAULT_DOMAIN, ONION_DOMAIN};
    private static final String[] ATTACHMENT_KEYS = new String[] { "file", "file2", "file3", "file4", "file5" };
    private static final Pattern PROTECTED_URL_PATTERN = Pattern.compile("<a[^>]*href=\"https?://privatelink.de/\\?([^\"]*)\"[^>]*>");
    private static final Pattern CAPTCHA_BASE64 = Pattern.compile("data:image/png;base64,([^\"]+)\"");

    public LolifoxModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    @Override
    public String getChanName() {
        return CHAN_NAME;
    }

    @Override
    public String getDisplayingName() {
        return "LOLIFOX";
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_lolifox, null);
    }

    @Override
    protected String getUsingDomain() {
        return preferences.getBoolean(getSharedKey(PREF_KEY_USE_ONION), false) ? ONION_DOMAIN : DEFAULT_DOMAIN;
    }

    @Override
    protected String getCloudflareCookieDomain() {
        return DEFAULT_DOMAIN;
    }

    @Override
    protected String[] getAllDomains() {
        return DOMAINS;
    }

    @Override
    protected boolean useHttps() {
        return !preferences.getBoolean(getSharedKey(PREF_KEY_USE_ONION), false) && useHttps(true);
    }

    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        Context context = preferenceGroup.getContext();
        addPasswordPreference(preferenceGroup);
        CheckBoxPreference httpsPref = addHttpsPreference(preferenceGroup, true);
        CheckBoxPreference onionPref = new LazyPreferences.CheckBoxPreference(context);
        onionPref.setTitle(R.string.pref_use_onion);
        onionPref.setSummary(R.string.pref_use_onion_summary);
        onionPref.setKey(getSharedKey(PREF_KEY_USE_ONION));
        onionPref.setDefaultValue(false);
        onionPref.setDisableDependentsState(true);
        preferenceGroup.addPreference(onionPref);
        httpsPref.setDependency(getSharedKey(PREF_KEY_USE_ONION));
        addProxyPreferences(preferenceGroup);
        addClearCookiesPreference(preferenceGroup);
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.timeZoneId = "GMT";
        model.ignoreEmailIfSage = false;
        model.markType = BoardModel.MARK_BBCODE;
        return model;
    }

    @Override
    protected PostModel mapPostModel(JSONObject object, String boardName) {
        PostModel model = super.mapPostModel(object, boardName);
        model.comment = RegexUtils.replaceAll(model.comment, PROTECTED_URL_PATTERN, "<a href=\"$1\">");
        return model;
    }

    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        getBoard(boardName, listener, task);
        needNewThreadCaptcha = threadNumber == null ?
                boardsThreadCaptcha.contains(boardName) :
                boardsPostCaptcha.contains(boardName);
        if (needNewThreadCaptcha) {
            String url = getUsingUrl() + "8chan-captcha/entrypoint.php?mode=get&extra=abcdefghijklmnopqrstuvwxyz";
            HttpRequestModel request = HttpRequestModel.builder().setGET()
                    .setCustomHeaders(new Header[] { new BasicHeader(HttpHeaders.CACHE_CONTROL, "max-age=0") }).build();
            JSONObject jsonResponse = HttpStreamer.getInstance().getJSONObjectFromUrl(url, request, httpClient, listener, task, false);
            Matcher base64Matcher = CAPTCHA_BASE64.matcher(jsonResponse.optString("captchahtml"));
            if (jsonResponse.has("cookie") && base64Matcher.find()) {
                byte[] bitmap = Base64.decode(base64Matcher.group(1), Base64.DEFAULT);
                newThreadCaptchaId = jsonResponse.getString("cookie");
                CaptchaModel captcha = new CaptchaModel();
                captcha.type = CaptchaModel.TYPE_NORMAL;
                captcha.bitmap = BitmapFactory.decodeByteArray(bitmap, 0, bitmap.length);
                return captcha;
            }
        }
        return null;
    }

    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        UrlPageModel urlModel = new UrlPageModel();
        urlModel.chanName = getChanName();
        urlModel.boardName = model.boardName;
        if (model.threadNumber == null) {
            urlModel.type = UrlPageModel.TYPE_BOARDPAGE;
            urlModel.boardPage = UrlPageModel.DEFAULT_FIRST_PAGE;
        } else {
            urlModel.type = UrlPageModel.TYPE_THREADPAGE;
            urlModel.threadNumber = model.threadNumber;
        }
        String refererUrl = buildUrl(urlModel);
        List<Pair<String, String>> fields;
        try {
             fields = VichanAntiBot.getFormValues(refererUrl, task, httpClient);
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, refererUrl);
            throw e;
        }

        if (task != null && task.isCancelled()) throw new InterruptedException("interrupted");
        String url = getUsingUrl() + "post.php";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().
                setCharset(Charset.forName("UTF-8")).setDelegates(listener, task);
        String key;
        for (Pair<String, String> pair : fields) {
            key = pair.getKey();
            if (pair.getKey().equals("no-bump") && !model.sage) continue;
            if (pair.getKey().equals("spoiler") && !model.custommark) continue;
            if (key.equals("captcha_cookie") && !needNewThreadCaptcha) {
                boardsThreadCaptcha.add(model.boardName);
                if (model.threadNumber != null) {
                    boardsPostCaptcha.add(model.boardName);
                }
                throw new Exception("Please complete your CAPTCHA");
            }
            String val;
            switch (key) {
                case "name": val = model.name; break;
                case "email": val = model.email; break;
                case "subject": val = model.subject; break;
                case "body": val = model.comment; break;
                case "password": val = model.password; break;
                case "no-bump":
                case "spoiler": val = "on"; break;
                case "captcha_text": val = model.captchaAnswer; break;
                case "captcha_cookie": val = newThreadCaptchaId == null ? "" : newThreadCaptchaId; break;
                default: val = pair.getValue();
            }
            if (key.equals("file")) {
                if (model.attachments != null && model.attachments.length > 0) {
                    for (int i = 0; i < model.attachments.length; ++i) {
                        postEntityBuilder.addFile(ATTACHMENT_KEYS[i], model.attachments[i], model.randomHash);
                    }
                } else {
                    postEntityBuilder.addPart(key, new ByteArrayBody(new byte[0], ""));
                }
            } else {
                postEntityBuilder.addString(key, val);
            }
        }
        postEntityBuilder.addString("json_response", "1");

        Header[] customHeaders = new Header[]{new BasicHeader(HttpHeaders.REFERER, refererUrl)};
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build())
                .setCustomHeaders(customHeaders).setNoRedirect(true).build();
        JSONObject json;
        try {
            json = HttpStreamer.getInstance().getJSONObjectFromUrl(url, request, httpClient, listener, task, false);
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        }
        if (json.has("error")) {
            String error = json.optString("error");
            if (error.equals("true") && json.optBoolean("banned"))
                throw new Exception("You are banned! ;_;");
            if (error.contains("<script")) {
                error = error.replaceAll("<script.*?</script>", "");
            }
            throw new Exception(error);
        } else {
            String redirect = json.optString("redirect", "");
            if (redirect.length() > 0) return fixRelativeUrl(redirect);
            /* vichan fallback */
            String id = json.optString("id", "");
            if (id.length() > 0) {
                urlModel = new UrlPageModel();
                urlModel.chanName = getChanName();
                urlModel.type = UrlPageModel.TYPE_THREADPAGE;
                urlModel.boardName = model.boardName;
                if (model.threadNumber == null) {
                    urlModel.threadNumber = id;
                } else {
                    urlModel.threadNumber = model.threadNumber;
                    urlModel.postNumber = id;
                }
                return buildUrl(urlModel);
            }
        }
        throw new Exception("Unknown Error");
    }

    @Override
    public String deletePost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = getUsingUrl() + "post.php";
        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        pairs.add(new BasicNameValuePair("board", model.boardName));
        pairs.add(new BasicNameValuePair("delete_" + model.postNumber, "on"));
        if (model.onlyFiles) pairs.add(new BasicNameValuePair("file", "on"));
        pairs.add(new BasicNameValuePair("password", model.password));
        pairs.add(new BasicNameValuePair("delete", ""));
        pairs.add(new BasicNameValuePair("json_response", "1"));

        UrlPageModel refererPage = new UrlPageModel();
        refererPage.type = UrlPageModel.TYPE_THREADPAGE;
        refererPage.chanName = getChanName();
        refererPage.boardName = model.boardName;
        refererPage.threadNumber = model.threadNumber;
        Header[] customHeaders = new Header[]{new BasicHeader(HttpHeaders.REFERER, buildUrl(refererPage))};
        HttpRequestModel request = HttpRequestModel.builder().
                setPOST(new UrlEncodedFormEntity(pairs, "UTF-8")).setCustomHeaders(customHeaders).setNoRedirect(true).build();
        JSONObject jsonResponse;
        try {
            jsonResponse = HttpStreamer.getInstance().getJSONObjectFromUrl(url, request, httpClient, listener, task, false);
            String error = jsonResponse.optString("error");
            if (error.length() > 0) throw new Exception(error);
        } catch (HttpWrongStatusCodeException e) {
            if (canCloudflare()) checkCloudflareError(e, url);
            throw e;
        }
        return null;
    }

    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        return super.parseUrl(url.replaceAll("\\+\\w+.html", ".html"));
    }

}
