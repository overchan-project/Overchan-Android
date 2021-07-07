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

package nya.miku.wishmaster.chans.monaba;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceGroup;
import android.support.v4.content.res.ResourcesCompat;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.client.HttpClient;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.entity.mime.content.ByteArrayBody;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import cz.msebera.android.httpclient.message.BasicHeader;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractWakabaModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.api.models.DeletePostModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.ChanModels;
import nya.miku.wishmaster.api.util.LazyPreferences;
import nya.miku.wishmaster.api.util.UrlPathUtils;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;
import nya.miku.wishmaster.lib.MimeTypes;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONException;
import nya.miku.wishmaster.lib.org_json.JSONObject;

import static nya.miku.wishmaster.chans.monaba.HaibaneConstants.*;

public class HaibaneModule extends AbstractWakabaModule {
    private Map<String, BoardModel> boardsMap = null;
    private Map<String, Boolean> boardsCaptchaStatusMap = null;
    private Map<String, String> postIds = null;
    /* Session cookie changes with every captcha request. No need to save it. */
    private String captchaCookie = null;

    public HaibaneModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    @Override
    public String getChanName() {
        return CHAN_NAME;
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
    public String getDisplayingName() {
        return DISPLAYING_NAME;
    }

    @Override
    protected boolean canCloudflare() {
        return true;
    }

    @Override
    protected boolean canHttps() {
        return true;
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_haibane, null);
    }

    @Override
    public void clearCookies() {
        super.clearCookies();
        captchaCookie = null;
    }

    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        String url = getUsingUrl() + "api/boards";
        List<SimpleBoardModel> list = new ArrayList<>();
        Map<String, BoardModel> newMap = new HashMap<>();
        JSONObject jsonResponse = downloadJSONObject(url, oldBoardsList != null, listener, task);
        if (jsonResponse == null) return oldBoardsList;

        Iterator<String> it = jsonResponse.keys();
        while (it.hasNext()) {
            Object obj = jsonResponse.get(it.next());
            if (obj instanceof JSONArray) {
                JSONArray boards = (JSONArray) obj;
                List<SimpleBoardModel> partialList = new ArrayList<>();
                for (int i = 0; i < boards.length(); i++) {
                    JSONObject boardJson = boards.getJSONObject(i);
                    BoardModel board = MonabaJsonMapper.mapBoardModel(boardJson, boardJson.getString("name"));
                    newMap.put(board.boardName, board);
                    partialList.add(new SimpleBoardModel(board));
                }
                Collections.sort(partialList, new Comparator<SimpleBoardModel>() {
                    @Override
                    public int compare(SimpleBoardModel m1, SimpleBoardModel m2) {
                        return m1.boardName.compareTo(m2.boardName);
                    }
                });
                list.addAll(partialList);
            }
        }
        boardsMap = newMap;
        SimpleBoardModel[] result = new SimpleBoardModel[list.size()];
        boolean[] copied = new boolean[list.size()];
        int curIndex = 0;
        for (String category : CATEGORIES) {
            for (int i=0; i<list.size(); ++i) {
                if (list.get(i).boardCategory.equals(category)) {
                    result[curIndex++] = list.get(i);
                    copied[i] = true;
                }
            }
        }
        for (int i=0; i<list.size(); ++i) {
            if (!copied[i]) {
                result[curIndex++] = list.get(i);
            }
        }
        return result;
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        if (boardsMap == null) boardsMap = new HashMap<>();
        if (boardsCaptchaStatusMap == null) boardsCaptchaStatusMap = new HashMap<>();
        if (boardsMap.containsKey(shortName)&& boardsCaptchaStatusMap.containsKey(shortName)) {
            return boardsMap.get(shortName);
        } else {
            String url = getUsingUrl() + "api/boards/" + shortName + "/page/0";
            JSONObject boardJson;
            try {
                JSONObject jsonResponse = downloadJSONObject(url, false, listener, task);
                boardJson = jsonResponse.getJSONObject("board");
            } catch (Exception e) {
                boardJson = new JSONObject();
            }
            BoardModel model = MonabaJsonMapper.mapBoardModel(boardJson, shortName);
            boardsMap.put(shortName, model);
            boardsCaptchaStatusMap.put(shortName, boardJson.optBoolean("enableCaptcha", true));
            return model;
        }
    }

    @Override
    public ThreadModel[] getThreadsList(String boardName, int page, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        String url = getUsingUrl() + "api/boards/" + boardName + "/page/" + page;
        JSONObject jsonResponse = downloadJSONObject(url, oldList != null, listener, task);
        if (jsonResponse == null) return oldList;
        try {
            JSONObject boardJson = jsonResponse.getJSONObject("board");
            if (boardsMap == null) boardsMap = new HashMap<>();
            boardsMap.put(boardName, MonabaJsonMapper.mapBoardModel(boardJson, boardName));
            if (boardsCaptchaStatusMap == null) boardsCaptchaStatusMap = new HashMap<>();
            boardsCaptchaStatusMap.put(boardName, boardJson.optBoolean("enableCaptcha", true));
        } catch (Exception e) {}
        JSONArray threads = jsonResponse.getJSONArray("threads");
        ThreadModel[] result = new ThreadModel[threads.length()];
        for (int i=0, len=threads.length(); i<len; ++i) {
            result[i] = MonabaJsonMapper.mapThreadModel(threads.getJSONObject(i));
        }
        return result;
    }

    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        String url = getUsingUrl() + "api/boards/"+ boardName + "/threads/" + threadNumber;
        JSONObject jsonResponse = downloadJSONObject(url, oldList != null, listener, task);
        if (jsonResponse == null) return oldList;
        PostModel[] result = MonabaJsonMapper.mapThreadModel(jsonResponse).posts;
        if (oldList != null) {
            result = ChanModels.mergePostsLists(Arrays.asList(oldList), Arrays.asList(result));
        }
        return result;
    }

    @Override
    public ThreadModel[] getCatalog(
            String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList) throws Exception {
        String url = getUsingUrl() + "api/catalog/" + boardName;
        JSONObject jsonResponse = downloadJSONObject(url, oldList != null, listener, task);
        if (jsonResponse == null) return oldList;
        JSONArray threadsJson = jsonResponse.optJSONArray("posts");
        ThreadModel[] result = new ThreadModel[threadsJson.length()];
        for (int i = 0, len = threadsJson.length(); i < len; ++i) {
            result[i] = MonabaJsonMapper.mapCatalogThreadModel(threadsJson.getJSONArray(i));
        }
        return result;
    }

    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        if (!boardsCaptchaStatusMap.get(boardName)) return null;
        String captchaUrl = getUsingUrl() + "api/captcha";
        JSONObject jsonResponse = downloadJSONObject(captchaUrl, false, listener, task);
        String captchaPath = jsonResponse.optString("path");
        if (captchaPath.length() > 0) {
            CaptchaModel captchaModel = downloadCaptcha(fixRelativeUrl(captchaPath), listener, task);
            for (Cookie cookie: httpClient.getCookieStore().getCookies()) {
                if (cookie.getName().equals(SESSION_COOKIE_NAME) && cookie.getDomain().contains(getUsingDomain())) {
                    captchaCookie = cookie.getValue();
                    break;
                }
            }
            captchaModel.adaptable = false;
            return captchaModel;
        } else {
            throw new Exception("Couldn't get captcha");
        }
    }

    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        if (model.threadNumber == null && model.subject.length() == 0) {
            throw new Exception("Thread subject is required");
        }
        boolean captchaRequired = boardsCaptchaStatusMap.get(model.boardName);
        if (captchaRequired) {
            if (captchaCookie == null) throw new Exception("Cookie is missing, reloading captcha");
            if (model.captchaAnswer.length() > 0) {
                String checkCaptchaUrl = getUsingUrl() + "captcha/check/" + Uri.encode(model.captchaAnswer);
                JSONObject captchaResult;
                try {
                    captchaResult = downloadJSONObject(checkCaptchaUrl, false, listener, task);
                    if (!captchaResult.getBoolean("result")) {
                        throw new Exception("Wrong captcha");
                    }
                } catch (HttpWrongStatusCodeException | JSONException ignored) {}
            } else {
                throw new Exception("Enter captcha");
            }
        }
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
        String url = buildUrl(urlModel);
        List<Pair<String, String>> fields;
        try {
            fields = MonabaAntiBot.getFormValues(url, task, httpClient);
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        }
        if (task != null && task.isCancelled()) throw new InterruptedException("interrupted");

        if (captchaRequired) {
            BasicClientCookie c = new BasicClientCookie(SESSION_COOKIE_NAME, captchaCookie);
            c.setDomain(getUsingDomain());
            c.setPath("/");
            httpClient.getCookieStore().addCookie(c);
            captchaCookie = null;
        }

        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().
                setCharset(Charset.forName("UTF-8")).setDelegates(listener, task);
        String key;
        int filesCounter = 0, ratingsCounter = 0;
        int attachmentsSize = (model.attachments != null) ? model.attachments.length : 0;
        for (Pair<String, String> pair : fields) {
            key = pair.getKey();
            if (pair.getKey().equals("f6") && !model.sage) continue;
            String val = null;
            switch (key) {
                case "f1": val = model.name; break;
                case "f2": val = model.subject; break;
                case "f3": val = model.comment; break;
                case "f4": val = model.captchaAnswer; break;
                case "f5": val = "1"; break;  //noko
                case "postpassword":
                    val = model.password.length() == 0 ? getDefaultPassword() : model.password;
                    break;
                case "f7": case "f8": case "f9": case "f10": case "f11":
                case "f12": case "f13": case "f14": case "f15": case "f16":
                    if (filesCounter < attachmentsSize) {
                        String ext = model.attachments[filesCounter].getName().
                                substring(model.attachments[filesCounter].getName().lastIndexOf('.') + 1);
                        String mime = MimeTypes.forExtension(ext);
                        postEntityBuilder.addFile(key, model.attachments[filesCounter], mime, model.randomHash);
                        ++filesCounter;
                    } else {
                        postEntityBuilder.addPart(key, new ByteArrayBody(new byte[0], ""));
                    }
                    break;
                case "f17": case "f18": case "f19": case "f20": case "f21":
                case "f22": case "f23": case "f24": case "f25": case "f26":
                    if (ratingsCounter < attachmentsSize) {
                        val = (model.icon >= 0 && model.icon < RATINGS.length) ?
                                Integer.toString(model.icon + 1) : "1";
                        ++ratingsCounter;
                    } else {
                        val = "";
                    }
                    break;
                default:
                    val = pair.getValue();
                    break;
            }
            if (val != null) postEntityBuilder.addString(key, val);
        }

        Header[] customHeaders = new Header[] {
                new BasicHeader("X-Requested-With", "XMLHttpRequest"),
                new BasicHeader(HttpHeaders.REFERER, url)
        };
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).
                setCustomHeaders(customHeaders).setNoRedirect(true).build();
        HttpResponseModel response = null;
        try {
            response = HttpStreamer.getInstance().getFromUrl(url, request, httpClient, null, task);
            if (response.statusCode == 303 || response.statusCode == 200) {
                String textResponse;
                if (response.statusCode == 200) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                    IOUtils.copyStream(response.stream, output);
                    textResponse = output.toString("UTF-8");
                } else {
                    String location = fixRelativeUrl(response.locationHeader);
                    textResponse = HttpStreamer.getInstance().
                            getStringFromUrl(location, HttpRequestModel.DEFAULT_GET, httpClient, null, task, false);
                }
                if (textResponse.startsWith("{")) {
                    JSONObject jsonResponse = new JSONObject(textResponse);
                    if (jsonResponse.has("error")) {
                        throw new Exception(jsonResponse.optString("error", "Unknown error"));
                    }
                }
                Matcher errorMatcher = ERROR_PATTERN.matcher(textResponse);
                if (errorMatcher.find()) throw new Exception(errorMatcher.group(1));
                return null;
            } else if (response.statusCode == 400) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                IOUtils.copyStream(response.stream, output);
                String responseText = output.toString("UTF-8");
                if (responseText.startsWith("Error")) throw new Exception(responseText);
            }
            throw new Exception(response.statusCode + " - " + response.statusReason);
        } finally {
            if (response != null) response.release();
        }
    }

    private String obtainGlobalPostId(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        if (postIds == null) postIds = new HashMap<>();
        String key = model.boardName + "/" + model.postNumber;
        String globalId = postIds.get(key);
        if (globalId == null) {
            String url = getUsingUrl() + "ajax/post/" + model.boardName + "/" + model.postNumber;
            try {
                String response = HttpStreamer.getInstance().getStringFromUrl(url, HttpRequestModel.DEFAULT_GET, httpClient, listener, task, false);
                Matcher matcher = Pattern.compile("<input.+?name=\"postdelete\".+?value=\"(\\d+)\"").matcher(response);
                if (matcher.find()) {
                    globalId = matcher.group(1);
                    postIds.put(key, globalId);
                } else {
                    throw new Exception("Unable to get number of target post");
                }
            } catch (HttpWrongStatusCodeException e) {
                checkCloudflareError(e, url);
                throw e;
            }
        }
        return globalId;
    }

    @Override
    public String deletePost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = getUsingUrl() + "delete?postdelete=" + obtainGlobalPostId(model, listener, task)
                + "&postpassword=" + Uri.encode(model.password)
                + (model.onlyFiles ? "&onlyfiles=1" : "");
        HttpResponseModel response = null;
        try {
            response = HttpStreamer.getInstance().getFromUrl(url, HttpRequestModel.DEFAULT_GET, httpClient, listener, task);
            if (response.statusCode == 303 || response.statusCode == 200) {
                String htmlResponse;
                if (response.statusCode == 200) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                    IOUtils.copyStream(response.stream, output);
                    htmlResponse = output.toString("UTF-8");
                } else {
                    String location = fixRelativeUrl(response.locationHeader);
                    htmlResponse = HttpStreamer.getInstance().
                            getStringFromUrl(location, HttpRequestModel.DEFAULT_GET, httpClient, null, task, false);
                }
                if (htmlResponse.contains("Неправильный пароль"))
                    throw new Exception("Неправильный пароль");
                if (htmlResponse.contains("Wrong password"))
                    throw new Exception("Wrong password");
            } else {
                throw new Exception(response.statusCode + " - " + response.statusReason);
            }
        } finally {
            if (response != null) response.release();
        }
        return null;
    }

    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(getChanName())) throw new IllegalArgumentException("wrong chan");
        StringBuilder url = new StringBuilder(getUsingUrl());
        switch (model.type) {
            case UrlPageModel.TYPE_INDEXPAGE:
                break;
            case UrlPageModel.TYPE_BOARDPAGE:
                url.append(model.boardName);
                if (model.boardPage > 0) url.append("/page/").append(model.boardPage);
                break;
            case UrlPageModel.TYPE_THREADPAGE:
                url.append(model.boardName).append("/").append(model.threadNumber);
                if (model.postNumber != null && model.postNumber.length() > 0) url.append("#").append(model.postNumber);
                break;
            case UrlPageModel.TYPE_CATALOGPAGE:
                url.append("catalog/").append(model.boardName);
                break;
            case UrlPageModel.TYPE_OTHERPAGE:
                url.append(model.otherPath.startsWith("/") ? model.otherPath.substring(1) : model.otherPath);
                break;
            default:
                throw new IllegalArgumentException("wrong page type");
        }
        return url.toString();
    }

    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        String path = UrlPathUtils.getUrlPath(url, getAllDomains());
        if (path == null) throw new IllegalArgumentException("wrong domain");
        UrlPageModel model = new UrlPageModel();
        model.chanName = getChanName();

        if (path.length() == 0) {
            model.type = UrlPageModel.TYPE_INDEXPAGE;
            return model;
        }
        Matcher threadPage = THREADPAGE_PATTERN.matcher(path);
        if (threadPage.find()) {
            model.type = UrlPageModel.TYPE_THREADPAGE;
            model.boardName = threadPage.group(1);
            model.threadNumber = threadPage.group(2);
            model.postNumber = threadPage.group(3);
            return model;
        }
        Matcher catalogPage = CATALOGPAGE_PATTERN.matcher(path);
        if (catalogPage.find()) {
            model.type = UrlPageModel.TYPE_CATALOGPAGE;
            model.boardName = catalogPage.group(1);
            return model;
        }
        Matcher boardPage = BOARDPAGE_PATTERN.matcher(path);
        if (boardPage.find()) {
            model.type = UrlPageModel.TYPE_BOARDPAGE;
            model.boardName = boardPage.group(1);
            String page = boardPage.group(2);
            model.boardPage = (page == null) ? 0 : Integer.parseInt(page);
            return model;
        }
        model.type = UrlPageModel.TYPE_OTHERPAGE;
        model.otherPath = path;
        return model;
    }

    @Override
    protected JSONObject downloadJSONObject(String url, boolean checkIfModified, ProgressListener listener, CancellableTask task) throws Exception {
        try {
            HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModified).
                    setCustomHeaders(new Header[] { new BasicHeader(HttpHeaders.ACCEPT, "application/json") }).
                    build();
            JSONObject object = HttpStreamer.getInstance().getJSONObjectFromUrl(url, rqModel, httpClient, listener, task, canCloudflare());
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return object;
        } catch (HttpWrongStatusCodeException e) {
            if (canCloudflare()) checkCloudflareError(e, url);
            throw e;
        }
    }

    private static class MonabaAntiBot {
        private static final String START_FORM = "<form class=\"plain-post-form\" id=\"post-form\"";
        private static final String END_FORM = "</form>";
        static List<Pair<String, String>> getFormValues(String url, CancellableTask task, HttpClient httpClient) throws Exception {
            MonabaAntiBot reader = null;
            HttpResponseModel response = null;
            try {
                response = HttpStreamer.getInstance().getFromUrl(url, HttpRequestModel.DEFAULT_GET, httpClient, null, task);
                reader = new MonabaAntiBot(response.stream, START_FORM, END_FORM);
                return reader.readForm();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception e) {}
                }
                if (response != null) response.release();
            }
        }

        private StringBuilder readBuffer = new StringBuilder();
        private List<Pair<String, String>> result = null;

        private String currentName = null;
        private String currentValue = null;
        private boolean currentTextArea = false;
        private boolean currentReading = false;

        private final char[] start;
        private final char[][] filters;
        private final Reader in;

        private static final int FILTER_INPUT_OPEN = 0;
        private static final int FILTER_TEXT_AREA_OPEN = 1;
        private static final int FILTER_SELECT_OPEN = 2;
        private static final int FILTER_NAME_OPEN = 3;
        private static final int FILTER_VALUE_OPEN = 4;
        private static final int FILTER_TAG_CLOSE = 5;
        private static final int FILTER_TAG_BEFORE_CLOSE = 6;

        private MonabaAntiBot(InputStream in, String start, String end) {
            this.start = start.toCharArray();
            this.filters = new char[][] {
                    "<input".toCharArray(),
                    "<textarea".toCharArray(),
                    "<select".toCharArray(),
                    "name=\"".toCharArray(),
                    "value=\"".toCharArray(),
                    ">".toCharArray(),
                    "/".toCharArray(),
                    end.toCharArray()
            };
            this.in = new BufferedReader(new InputStreamReader(in));
        }

        private List<Pair<String, String>> readForm() throws IOException {
            result = new ArrayList<>();
            skipUntilSequence(start);
            int filtersCount = filters.length;
            int[] pos = new int[filtersCount];
            int[] len = new int[filtersCount];
            for (int i=0; i<filtersCount; ++i) len[i] = filters[i].length;

            int curChar;
            while ((curChar = in.read()) != -1) {
                for (int i=0; i<filtersCount; ++i) {
                    if (curChar == filters[i][pos[i]]) {
                        ++pos[i];
                        if (pos[i] == len[i]) {
                            if (i == filtersCount - 1) {
                                return result;
                            }
                            handleFilter(i);
                            pos[i] = 0;
                        }
                    } else {
                        if (pos[i] != 0) pos[i] = curChar == filters[i][0] ? 1 : 0;
                    }
                }
            }
            return result;
        }

        private void handleFilter(int i) throws IOException {
            switch (i) {
                case FILTER_INPUT_OPEN:
                case FILTER_SELECT_OPEN:
                    currentReading = true;
                    currentTextArea = false;
                    break;
                case FILTER_TEXT_AREA_OPEN:
                    currentReading = true;
                    currentTextArea = true;
                    break;
                case FILTER_NAME_OPEN:
                    currentName = StringEscapeUtils.unescapeHtml4(readUntilSequence("\"".toCharArray()));
                    break;
                case FILTER_VALUE_OPEN:
                    currentValue = StringEscapeUtils.unescapeHtml4(readUntilSequence("\"".toCharArray()));
                    break;
                case FILTER_TAG_CLOSE:
                    if (currentTextArea) {
                        currentValue = StringEscapeUtils.unescapeHtml4(readUntilSequence("<".toCharArray()));
                    }
                    if (currentReading && currentName != null) {
                        result.add(Pair.of(currentName, currentValue != null ? currentValue : ""));
                    }
                    currentName = null;
                    currentValue = null;
                    currentReading = false;
                    currentTextArea = false;
                    break;
                case FILTER_TAG_BEFORE_CLOSE: // <textarea ..... />
                    currentTextArea = false;
                    break;
            }
        }

        private void skipUntilSequence(char[] sequence) throws IOException {
            int len = sequence.length;
            if (len == 0) return;
            int pos = 0;
            int curChar;
            while ((curChar = in.read()) != -1) {
                if (curChar == sequence[pos]) {
                    ++pos;
                    if (pos == len) break;
                } else {
                    if (pos != 0) pos = curChar == sequence[0] ? 1 : 0;
                }
            }
        }

        private String readUntilSequence(char[] sequence) throws IOException {
            int len = sequence.length;
            if (len == 0) return "";
            readBuffer.setLength(0);
            int pos = 0;
            int curChar;
            while ((curChar = in.read()) != -1) {
                readBuffer.append((char) curChar);
                if (curChar == sequence[pos]) {
                    ++pos;
                    if (pos == len) break;
                } else {
                    if (pos != 0) pos = curChar == sequence[0] ? 1 : 0;
                }
            }
            int buflen = readBuffer.length();
            if (buflen >= len) {
                readBuffer.setLength(buflen - len);
                return readBuffer.toString();
            } else {
                return "";
            }
        }

        public void close() throws IOException {
            in.close();
        }
    }
}
