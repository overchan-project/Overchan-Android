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

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.message.BasicHeader;
import cz.msebera.android.httpclient.util.TextUtils;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.lib.base64.Base64;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public class SmugModule extends InfinityModule {
    private static final String CHAN_NAME = "smuglo.li";
    private static final String DEFAULT_DOMAIN = "smuglo.li";
    private static final String ONION_DOMAIN = "bhm5koavobq353j54qichcvzr6uhtri6x4bjjy4xkybgvxkzuslzcqid.onion";
    private static final String[] DOMAINS = new String[] { DEFAULT_DOMAIN, ONION_DOMAIN, "smugloli.net" };
    private static final String[] ATTACHMENT_FORMATS = new String[] { "jpg", "jpeg", "gif", "png",
            "webm", "mp4", "mp3", "ogg", "flac", "txt", "svg", "zip", "rar", "cbz", "cbr", "7z", "gz",
            "tar", "avi", "opus", "swf", "pdf" };
    private static final Pattern CAPTCHA_BASE64 = Pattern.compile("data:image/png;base64,([^\"]*)\"");

    public SmugModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    @Override
    public String getChanName() {
        return CHAN_NAME;
    }

    @Override
    public String getDisplayingName() {
        return "Smug";
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_smug, null);
    }

    @Override
    protected String getUsingDomain() {
        return preferences.getBoolean(getSharedKey(PREF_KEY_USE_ONION), false) ? ONION_DOMAIN : DEFAULT_DOMAIN;
    }

    @Override
    protected String[] getAllDomains() {
        return DOMAINS;
    }

    @Override
    protected boolean canCloudflare() {
        return false;
    }

    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        String url = getUsingUrl() + "webring.json";
        try {
            JSONObject json = downloadJSONObject(url, oldBoardsList != null, listener, task);
            if (json == null) return oldBoardsList;
            JSONArray boards = json.getJSONArray("boards");
            SimpleBoardModel[] list = new SimpleBoardModel[boards.length()];
            for (int i=0; i<boards.length(); ++i) {
                list[i] = new SimpleBoardModel();
                list[i].boardName = boards.getJSONObject(i).getString("uri");
                list[i].boardDescription = boards.getJSONObject(i).getString("title");
            }
            return list;
        } catch (Exception e) {
            return new SimpleBoardModel[0];
        }
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.timeZoneId = "UTC";
        model.attachmentsFormatFilters = ATTACHMENT_FORMATS;
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
        if (task != null && task.isCancelled()) throw new InterruptedException("interrupted");
        String url = getUsingUrl() + "post.php";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("name", model.name).
                addString("email", model.sage ? "sage" : model.email).
                addString("subject", model.subject).
                addString("body", model.comment).
                addString("post", model.threadNumber == null ? "New Topic" : "New Reply").
                addString("board", model.boardName);
        if (model.threadNumber != null) postEntityBuilder.addString("thread", model.threadNumber);
        if (model.custommark) postEntityBuilder.addString("spoiler", "on");
        postEntityBuilder.addString("password", TextUtils.isEmpty(model.password) ? getDefaultPassword() : model.password).
                addString("message", "").
                addString("json_response", "1");
        if (model.attachments != null) {
            String[] images = new String[] { "file", "file2", "file3", "file4", "file5" };
            for (int i=0; i<model.attachments.length; ++i) {
                postEntityBuilder.addFile(images[i], model.attachments[i], model.randomHash);
            }
        }
        if (needNewThreadCaptcha) {
            postEntityBuilder.addString("captcha_text", model.captchaAnswer).addString("captcha_cookie", newThreadCaptchaId);
        }
        
        UrlPageModel refererPage = new UrlPageModel();
        refererPage.chanName = getChanName();
        refererPage.boardName = model.boardName;
        if (model.threadNumber == null) {
            refererPage.type = UrlPageModel.TYPE_BOARDPAGE;
            refererPage.boardPage = UrlPageModel.DEFAULT_FIRST_PAGE;
        } else {
            refererPage.type = UrlPageModel.TYPE_THREADPAGE;
            refererPage.threadNumber = model.threadNumber;
        }
        Header[] customHeaders = new Header[] { new BasicHeader(HttpHeaders.REFERER, buildUrl(refererPage)) };
        HttpRequestModel request =
                HttpRequestModel.builder().setPOST(postEntityBuilder.build()).setCustomHeaders(customHeaders).setNoRedirect(true).build();
        JSONObject json = HttpStreamer.getInstance().getJSONObjectFromUrl(url, request, httpClient, listener, task, false);
        if (json.has("error")) {
            String error = json.optString("error");
            if (error.equals("true") && json.optBoolean("banned")) throw new Exception("You are banned! ;_;");
            if (error.contains("you must use the hidden service for security reasons"))
                throw new Exception("To post over Tor, you must use the onion domain.");
            throw new Exception(error);
        } else {
            String redirect = json.optString("redirect", "");
            if (redirect.length() > 0) return fixRelativeUrl(redirect);
            return null;
        }
    }
}
