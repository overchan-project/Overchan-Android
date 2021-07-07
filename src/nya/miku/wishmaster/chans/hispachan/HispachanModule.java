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

package nya.miku.wishmaster.chans.hispachan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;
import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractKusabaModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BadgeIconModel;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.DeletePostModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.api.util.WakabaReader;
import nya.miku.wishmaster.api.util.WakabaUtils;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.recaptcha.Recaptcha2;
import nya.miku.wishmaster.http.recaptcha.Recaptcha2solved;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;

public class HispachanModule extends AbstractKusabaModule {
    static final String CHAN_NAME = "hispachan";
    private static final String[] DOMAINS = { "www.hispachan.org", "hispachan.org" };
    private static final String[] EMAIL_OPTIONS = new String[] {
            "Opciones", "sage", "noko", "dado", "OP", "fortuna", "nokosage", "dadosage", "OPsage", "fortunasage" };

    private static final String RECAPTCHA_KEY = "6Ld8dgkTAAAAAB9znPHkLX31dnP80eIQvY4YnXWc";

    private static final Pattern ERROR_POSTING = Pattern.compile("<div class=\"diverror\"[^>]*>(?:.*<br[^>]*>)?(.*?)</div>", Pattern.DOTALL);

    public HispachanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    @Override
    public String getChanName() {
        return CHAN_NAME;
    }

    @Override
    public String getDisplayingName() {
        return "Hispachan";
    }

    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_hispachan, null);
    }

    @Override
    protected String getUsingDomain() {
        return DOMAINS[0];
    }

    @Override
    protected String[] getAllDomains() {
        return DOMAINS;
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
    protected WakabaReader getKusabaReader(InputStream stream, UrlPageModel urlModel) {
        return new HispachanReader(stream, canCloudflare());
    }

    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        String url = getUsingUrl();
        HttpResponseModel responseModel = null;
        HispachanBoardsListReader in = null;
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(oldBoardsList != null).build();
        try {
            responseModel = HttpStreamer.getInstance().getFromUrl(url, rqModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                in = new HispachanBoardsListReader(responseModel.stream);
                if (task != null && task.isCancelled()) throw new Exception("interrupted");
                return in.readBoardsList();
            } else {
                if (responseModel.notModified()) return null;
                byte[] html = null;
                try {
                    ByteArrayOutputStream byteStream = new ByteArrayOutputStream(1024);
                    IOUtils.copyStream(responseModel.stream, byteStream);
                    html = byteStream.toByteArray();
                } catch (Exception e) {
                }
                throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode + " - " + responseModel.statusReason, html);
            }
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, url);
            throw e;
        } catch (Exception e) {
            if (responseModel != null) HttpStreamer.getInstance().removeFromModifiedMap(url);
            throw e;
        } finally {
            IOUtils.closeQuietly(in);
            if (responseModel != null) responseModel.release();
        }
    }

    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.defaultUserName = "Anónimo";
        model.allowDeleteFiles = false;
        model.allowNames = false;
        model.allowSage = false;
        model.allowEmails = false;
        model.allowCustomMark = "OCIO".equalsIgnoreCase(model.boardCategory);
        model.customMarkDescription = "Spoiler";
        model.allowIcons = true;
        model.iconDescriptions = EMAIL_OPTIONS;
        model.markType = BoardModel.MARK_BBCODE;
        model.catalogAllowed = true;
        return model;
    }

    @Override
    public ThreadModel[] getCatalog(String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        UrlPageModel urlModel = new UrlPageModel();
        urlModel.chanName = getChanName();
        urlModel.type = UrlPageModel.TYPE_CATALOGPAGE;
        urlModel.boardName = boardName;
        String url = buildUrl(urlModel);

        HttpResponseModel responseModel = null;
        HispachanCatalogReader in = null;
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(oldList != null).build();
        try {
            responseModel = HttpStreamer.getInstance().getFromUrl(url, rqModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                in = new HispachanCatalogReader(responseModel.stream);
                if (task != null && task.isCancelled()) throw new Exception("interrupted");
                return in.readPage();
            } else {
                if (responseModel.notModified()) return oldList;
                byte[] html = null;
                try {
                    ByteArrayOutputStream byteStream = new ByteArrayOutputStream(4096);
                    IOUtils.copyStream(responseModel.stream, byteStream);
                    html = byteStream.toByteArray();
                } catch (Exception e) {}
                if (html != null) {
                    checkCloudflareError(new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusReason, html), url);
                }
                throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode + " - " + responseModel.statusReason);
            }
        } catch (Exception e) {
            if (responseModel != null) HttpStreamer.getInstance().removeFromModifiedMap(url);
            throw e;
        } finally {
            IOUtils.closeQuietly(in);
            if (responseModel != null) responseModel.release();
        }
    }

    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = getUsingUrl() + "board.php";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task);
        String emOptions = (model.icon > 0 && model.icon < EMAIL_OPTIONS.length) ? EMAIL_OPTIONS[model.icon] : "noko";
        postEntityBuilder.
            addString("board", model.boardName).
            addString("replythread", model.threadNumber == null ? "0" : model.threadNumber).
            addString("name", model.name).
            addString("em", emOptions).
            addString("subject", model.subject).
            addString("message", model.comment).
            addString("postpassword", model.password);
        setSendPostEntityAttachments(model, postEntityBuilder);

        String checkCaptchaUrl = getUsingUrl() + "cl_captcha.php?board="+ model.boardName + "&v" + (model.threadNumber != null ? "&rp" : "");
        if (HttpStreamer.getInstance().getStringFromUrl(checkCaptchaUrl, HttpRequestModel.DEFAULT_GET,
                httpClient, null, task, false).equals("1")) {
            String response = Recaptcha2solved.pop(RECAPTCHA_KEY);
            if (response == null) {
                throw Recaptcha2.obtain(getUsingUrl(), RECAPTCHA_KEY, null, CHAN_NAME, false);
            }
            postEntityBuilder.addString("g-recaptcha-response", response);
        }

        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).setNoRedirect(true).build();
        HttpResponseModel response = null;
        try {
            response = HttpStreamer.getInstance().getFromUrl(url, request, httpClient, null, task);
            if (response.statusCode == 302) {
                if (!emOptions.startsWith("noko")) return null;
                for (Header header : response.headers) {
                    if (header != null && HttpHeaders.LOCATION.equalsIgnoreCase(header.getName())) {
                        return fixRelativeUrl(header.getValue());
                    }
                }
            } else if (response.statusCode == 200) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                IOUtils.copyStream(response.stream, output);
                String htmlResponse = output.toString("UTF-8");
                if (htmlResponse.contains("<div class=\"divban\">")) {
                    throw new Exception("¡ESTÁS BANEADO!");
                }
                Matcher errorMatcher = ERROR_POSTING.matcher(htmlResponse);
                if (errorMatcher.find()) {
                    throw new Exception(StringEscapeUtils.unescapeHtml4(errorMatcher.group(1).trim()));
                }
            } else {
                throw new Exception(response.statusCode + " - " + response.statusReason);
            }
        } finally {
            if (response != null) response.release();
        }
        return null;
    }

    @Override
    protected void checkDeletePostResult(DeletePostModel model, String result) throws Exception {
        if (StringEscapeUtils.unescapeHtml4(result).contains("No puedes realizar esta acción")) {
            throw new Exception("No puedes realizar esta acción");
        }
    }

    @Override
    protected void checkReportPostResult(DeletePostModel model, String result) throws Exception {
        if (StringEscapeUtils.unescapeHtml4(result).contains("Post reportado correctamente")) return;
        throw new Exception(result);
    }

    @Override
    protected String getDeleteFormValue(DeletePostModel model) {
        return "Eliminar";
    }

    @Override
    protected String getReportFormValue(DeletePostModel model) {
        return "Reportar";
    }

    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(getChanName())) throw new IllegalArgumentException("wrong chan");
        if (model.type == UrlPageModel.TYPE_CATALOGPAGE) return getUsingUrl() + model.boardName + "/catalog.html";
        return WakabaUtils.buildUrl(model, getUsingUrl());
    }

    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        UrlPageModel model = WakabaUtils.parseUrl(url, getChanName(), getUsingDomain());
        if (model.type == UrlPageModel.TYPE_OTHERPAGE && model.otherPath != null && model.otherPath.endsWith("/catalog.html")) {
            model.type = UrlPageModel.TYPE_CATALOGPAGE;
            model.boardName = model.otherPath.substring(0, model.otherPath.length() - 13);
            model.otherPath = null;
        }
        return model;
    }

    @SuppressLint("SimpleDateFormat")
    private static class HispachanReader extends KusabaReader {
        private static final DateFormat DATE_FORMAT;
        static {
            DATE_FORMAT = new SimpleDateFormat("dd/MM/yy HH:mm Z");
            DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
        }
        private static final String OP_TAG = "<span class=\"op\"";

        private static final int FILTER_FILE_SIZE = 0;
        private static final int FILTER_PX_SIZE = 1;
        private static final int FILTER_FILE_NAME = 2;
        private static final int FILTER_DATE = 3;
        private static final int FILTER_FILES_COUNT = 4;
        private static final int FILTER_OP_POST = 5;

        private static final char[][] FILTERS_OPEN = {
                "style=\"font-size: 85%;\">".toCharArray(),
                "class=\"moculto\">".toCharArray(),
                "class=\"nombrefile\">".toCharArray(),
                "data-date=\"".toCharArray(),
                "<span class=\"typecount\"".toCharArray(),
                OP_TAG.toCharArray(),
        };
        private static final char[][] FILTERS_CLOSE = {
                "<".toCharArray(),
                "</span".toCharArray(),
                "</span".toCharArray(),
                "\"".toCharArray(),
                "</span>".toCharArray(),
                null,
        };

        private static final Pattern ATTACHMENT_SIZE_PATTERN = Pattern.compile("([\\d.]+) ?([km])?b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        private static final Pattern ATTACHMENT_PX_SIZE_PATTERN = Pattern.compile("(\\d+)[x×](\\d+)"); // \u0078 \u00D7
        private static final Pattern ATTACHMENT_NAME_PATTERN = Pattern.compile("[\\s,]*([^<]+)");
        private static final Pattern POST_ID_COLOR_PATTERN = Pattern.compile("background-color: ?(#?\\w+)");
        private static final Pattern BADGE_ICON_PATTERN = Pattern.compile("<img [^>]*src=\"(.+?)\"(?:.*?title=\"(.+?)\")?");
        private static final Pattern FILES_COUNT_PATTERN = Pattern.compile("[IV].*?(\\d+)");
        private static final Pattern RED_TEXT_MARK_PATTERN = Pattern.compile("<span class=\"redtext\">(.*?)</span>");

        private static final int FILTERS_COUNT = FILTERS_OPEN.length;
        private static final int[] FILTER_LENGTHS = new int[FILTERS_COUNT];
        static {
            for (int i = 0; i< FILTERS_COUNT; ++i) FILTER_LENGTHS[i] = FILTERS_OPEN[i].length;
        }
        private int[] positions;
        private int currentAttachmentWidth, currentAttachmentHeight, currentAttachmentSize;
        private String currentAttachmentName;

        public HispachanReader(InputStream in, boolean canCloudflare) {
            super(in, DATE_FORMAT, canCloudflare, ~(FLAG_HANDLE_EMBEDDED_POST_POSTPROCESS|FLAG_OMITTED_STRING_REMOVE_HREF));
            positions = new int[FILTERS_COUNT];
        }

        @Override
        protected void customFilters(int ch) throws IOException {
            super.customFilters(ch);

            for (int i = 0; i< FILTERS_COUNT; ++i) {
                if (ch == FILTERS_OPEN[i][positions[i]]) {
                    ++positions[i];
                    if (positions[i] == FILTER_LENGTHS[i]) {
                        handleFilter(i);
                        positions[i] = 0;
                    }
                } else {
                    if (positions[i] != 0) positions[i] = ch == FILTERS_OPEN[i][0] ? 1 : 0;
                }
            }
        }

        private void handleFilter(int filterIndex) throws IOException {
            switch (filterIndex) {
                case FILTER_FILE_SIZE:
                    currentAttachmentSize = -1;
                    Matcher byteSizeMatcher = ATTACHMENT_SIZE_PATTERN.matcher(readUntilSequence(FILTERS_CLOSE[filterIndex]));
                    if (byteSizeMatcher.find()) {
                        try {
                            String digits = byteSizeMatcher.group(1);
                            int multiplier = 1;
                            String prefix = byteSizeMatcher.group(2);
                            if (prefix != null) {
                                if (prefix.equalsIgnoreCase("k")) multiplier = 1024;
                                else if (prefix.equalsIgnoreCase("m")) multiplier = 1024 * 1024;
                            }
                            currentAttachmentSize = Math.round(Float.parseFloat(digits) / 1024 * multiplier);
                        } catch (NumberFormatException e) {}
                    }
                    break;
                case FILTER_PX_SIZE:
                    currentAttachmentWidth = -1;
                    currentAttachmentHeight = -1;
                    Matcher pxMatcher = ATTACHMENT_PX_SIZE_PATTERN.matcher(readUntilSequence(FILTERS_CLOSE[filterIndex]));
                    if (pxMatcher.find()) {
                        currentAttachmentWidth = Integer.parseInt(pxMatcher.group(1));
                        currentAttachmentHeight = Integer.parseInt(pxMatcher.group(2));
                    }
                    break;
                case FILTER_FILE_NAME:
                    Matcher originalNameMatcher = ATTACHMENT_NAME_PATTERN.matcher(readUntilSequence(FILTERS_CLOSE[filterIndex]));
                    if (originalNameMatcher.find()) {
                        String originalName = originalNameMatcher.group(1).trim();
                        if (originalName.length() > 0) {
                            currentAttachmentName = StringEscapeUtils.unescapeHtml4(originalName);
                        }
                    }
                    break;
                case FILTER_DATE:
                    String date = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                    parseDate(date);
                    break;
                case FILTER_FILES_COUNT:
                    String omitted = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                    Matcher filesCountMatcher = FILES_COUNT_PATTERN.matcher(omitted);
                    if (filesCountMatcher.find()) {
                        currentThread.attachmentsCount += Integer.parseInt(filesCountMatcher.group(1));
                    }
                    break;
                case FILTER_OP_POST:
                    currentPost.op = true;
                    break;
            }
        }

        @Override
        protected void parseThumbnail(String imgTag) {
            super.parseThumbnail(imgTag);
            if (currentAttachments.size() > 0) {
                AttachmentModel attachment = currentAttachments.get(currentAttachments.size() - 1);
                attachment.width = currentAttachmentWidth;
                attachment.height = currentAttachmentHeight;
                attachment.size = currentAttachmentSize;
                attachment.originalName = currentAttachmentName;
            }
        }

        @Override
        protected void parseNameEmail(String raw) {
            if (raw.contains(OP_TAG)) {
                currentPost.op = true;
                super.parseNameEmail(raw.substring(0, raw.indexOf(OP_TAG)));
            } else super.parseNameEmail(raw);

            Matcher matcher = POST_ID_COLOR_PATTERN.matcher(raw);
            if (matcher.find()) {
                try {
                    currentPost.color = Color.parseColor(matcher.group(1));
                } catch (Exception e) {}
            }

            matcher = BADGE_ICON_PATTERN.matcher(raw);
            if (matcher.find()) {
                BadgeIconModel iconModel = new BadgeIconModel();
                iconModel.source = matcher.group(1);
                iconModel.description = StringEscapeUtils.unescapeHtml4(matcher.group(2));
                int currentIconsCount = currentPost.icons == null ? 0 : currentPost.icons.length;
                BadgeIconModel[] newIconsArray = new BadgeIconModel[currentIconsCount + 1];
                for (int i=0; i<currentIconsCount; ++i) newIconsArray[i] = currentPost.icons[i];
                newIconsArray[currentIconsCount] = iconModel;
                currentPost.icons = newIconsArray;
            }
        }

        @Override
        protected void postprocessPost(PostModel post) {
            super.postprocessPost(post);
            post.name = post.name.replace("\u00a0", " ").trim();
            post.comment = RegexUtils.replaceAll(post.comment, RED_TEXT_MARK_PATTERN, "<font color=\"#FF687F\">$1</font>");
        }
    }
}
