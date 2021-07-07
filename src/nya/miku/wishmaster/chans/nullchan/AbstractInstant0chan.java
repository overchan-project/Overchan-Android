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

package nya.miku.wishmaster.chans.nullchan;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.Resources;

import cz.msebera.android.httpclient.NameValuePair;
import cz.msebera.android.httpclient.message.BasicNameValuePair;
import nya.miku.wishmaster.api.AbstractKusabaModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BadgeIconModel;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.CaptchaModel;
import nya.miku.wishmaster.api.models.DeletePostModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.api.util.UrlPathUtils;
import nya.miku.wishmaster.api.util.WakabaReader;
import nya.miku.wishmaster.api.util.WakabaUtils;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONObject;

public abstract class AbstractInstant0chan extends AbstractKusabaModule {
    private static final String TAG = "AbstractInstant0chan";
    public AbstractInstant0chan(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    protected boolean availableUserboardsList() {
        return true;
    }

    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        List<SimpleBoardModel> boardsList = new ArrayList<>();
        String url = getUsingUrl() + "boards10.json";
        JSONArray json;
        try {
            json = downloadJSONArray(url, oldBoardsList != null, listener, task);
            if (json == null) return oldBoardsList;
            for (int i=0; i<json.length(); ++i) {
                String currentCategory = json.getJSONObject(i).optString("name");
                JSONArray boards = json.getJSONObject(i).getJSONArray("boards");
                for (int j=0; j<boards.length(); ++j) {
                    SimpleBoardModel model = new SimpleBoardModel();
                    model.chan = getChanName();
                    model.boardName = boards.getJSONObject(j).getString("dir");
                    model.boardDescription = StringEscapeUtils.unescapeHtml4(boards.getJSONObject(j).optString("desc", model.boardName));
                    model.boardCategory = currentCategory;
                    model.nsfw = model.boardName.equals("b") || currentCategory.equalsIgnoreCase("adult");
                    boardsList.add(model);
                }
            }
        } catch (Exception e) {}
        if (availableUserboardsList()) {
            url = getUsingUrl() + "boards20.json";
            try {
                json = downloadJSONArray(url, false, listener, task);
                for (int i=0; i<json.length(); ++i) {
                    SimpleBoardModel model = new SimpleBoardModel();
                    model.chan = getChanName();
                    model.boardName = json.getJSONObject(i).getString("name");
                    model.boardDescription = StringEscapeUtils.unescapeHtml4(json.getJSONObject(i).optString("desc", model.boardName));
                    model.boardCategory = "2.0";
                    model.nsfw = true;
                    boardsList.add(model);
                }
            } catch (Exception e) {}
        }
        return boardsList.toArray(new SimpleBoardModel[0]);
    }
    
    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.timeZoneId = "GMT+3";
        model.attachmentsMaxCount = 8;
        model.allowCustomMark = true;
        model.customMarkDescription = "Спойлер";
        model.requiredFileForNewThread = !shortName.equals("0");
        model.allowReport = BoardModel.REPORT_SIMPLE;
        model.allowNames = !shortName.equals("b");
        model.allowEmails = false;
        model.catalogAllowed = true;
        return model;
    }
    
    @Override
    protected WakabaReader getKusabaReader(InputStream stream, UrlPageModel urlModel) {
        if (urlModel != null && urlModel.chanName != null && urlModel.chanName.equals("expand")) {
            stream = new SequenceInputStream(new ByteArrayInputStream("<form id=\"delform\">".getBytes()), stream);
        }
        return new Instant0chanReader(stream, canCloudflare());
    }
    
    @Override
    public ThreadModel[] getCatalog(String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList) throws Exception {
        String url = getUsingUrl() + boardName + "/catalog.json";
        JSONArray response = downloadJSONArray(url, oldList != null, listener, task);
        if (response == null) return oldList;
        ThreadModel[] threads = new ThreadModel[response.length()];
        for (int i = 0; i < threads.length; ++i) {
            threads[i] = mapCatalogThreadModel(response.getJSONObject(i), boardName);
        }
        return threads;
    }
    
    private ThreadModel mapCatalogThreadModel(JSONObject json, String boardName) {
        ThreadModel model = new ThreadModel();
        model.threadNumber = json.optString("id", null);
        if (model.threadNumber == null) throw new RuntimeException();
        model.postsCount = json.optInt("reply_count", -2) + 1;
        model.attachmentsCount = json.optInt("images", -1);
        model.isClosed = json.optInt("locked", 0) != 0;
        model.isSticky = json.optInt("stickied", 0) != 0;
        
        PostModel opPost = new PostModel();
        opPost.number = model.threadNumber;
        opPost.name = StringEscapeUtils.unescapeHtml4(RegexUtils.removeHtmlSpanTags(json.optString("name")));
        opPost.subject = StringEscapeUtils.unescapeHtml4(json.optString("subject"));
        opPost.comment = json.optString("message");
        opPost.trip = json.optString("tripcode");
        opPost.timestamp = json.optLong("timestamp") * 1000L;
        opPost.parentThread = model.threadNumber;
        
        JSONArray jsonEmbeds = json.optJSONArray("embeds");
        if (jsonEmbeds != null && jsonEmbeds.length() > 0) {
            List<AttachmentModel> attachments = new ArrayList<>();
            for (int i = 0; i < jsonEmbeds.length(); i++) {
                JSONObject embed = jsonEmbeds.getJSONObject(i);
                String ext = embed.optString("file_type", "");
                String fileName = embed.optString("file", "");
                if (ext.length() == 0 || fileName.length() == 0) continue;
                
                AttachmentModel attachment = new AttachmentModel();
                switch (ext) {
                    case "jpg":
                    case "jpeg":
                    case "png":
                        attachment.type = AttachmentModel.TYPE_IMAGE_STATIC;
                        break;
                    case "gif":
                        attachment.type = AttachmentModel.TYPE_IMAGE_GIF;
                        break;
                    case "mp3":
                    case "ogg":
                        attachment.type = AttachmentModel.TYPE_AUDIO;
                        break;
                    case "webm":
                    case "mp4":
                        attachment.type = AttachmentModel.TYPE_VIDEO;
                        break;
                    case "you":
                        attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                        attachment.thumbnail = "https://img.youtube.com/vi/" + fileName + "/default.jpg";
                        attachment.path = "https://youtube.com/watch?v=" + fileName;
                        break;
                    case "cob":
                        attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                        attachment.path = "https://coub.com/view/" + fileName;
                        break;
                    case "vim":
                        attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                        attachment.path = "https://vimeo.com/" + fileName;
                        break;
                    default:
                        attachment.type = AttachmentModel.TYPE_OTHER_FILE;
                }
                if (attachment.type != AttachmentModel.TYPE_OTHER_NOTFILE) {
                    if (attachment.type != AttachmentModel.TYPE_AUDIO) {
                        attachment.thumbnail = "/" + boardName + "/thumb/" + fileName + "s."
                                + (attachment.type == AttachmentModel.TYPE_VIDEO ? "jpg" : ext);
                    }
                    attachment.path = "/" + boardName + "/src/" + fileName + "." + ext;
                    attachment.width = embed.optInt("image_w", -1);
                    attachment.height = embed.optInt("image_h", -1);
                }
                attachment.size = -1;
                attachments.add(attachment);
            }
            opPost.attachments = attachments.toArray(new AttachmentModel[0]);
        }
        model.posts = new PostModel[] { opPost };
        return model;
    }
    
    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        String captchaUrl = getUsingUrl() + "captcha.php?" + Math.random();
        CaptchaModel captchaModel = downloadCaptcha(captchaUrl, listener, task);
        captchaModel.type = CaptchaModel.TYPE_NORMAL;
        return captchaModel;
    }
    
    @Override
    protected void setSendPostEntityAttachments(SendPostModel model, ExtendedMultipartBuilder postEntityBuilder) throws Exception {
        if (model.attachments != null && model.attachments.length > 0) {
            for (int i = 0; i < model.attachments.length; i++) {
                postEntityBuilder.addFile("imagefile[]", model.attachments[i], model.randomHash);
                if (model.custommark) postEntityBuilder.addString("spoiler-" + i, "1");
            }
        }
    }
    
    @Override
    protected void setSendPostEntity(SendPostModel model, ExtendedMultipartBuilder postEntityBuilder) throws Exception {
        postEntityBuilder.
                addString("board", model.boardName).
                addString("replythread", model.threadNumber == null ? "0" : model.threadNumber).
                addString("name", model.name);
        if (model.sage) postEntityBuilder.addString("em", "sage");
        postEntityBuilder.
                addString("captcha", model.captchaAnswer).
                addString("subject", model.subject).
                addString("message", model.comment).
                addString("postpassword", model.password);
        setSendPostEntityAttachments(model, postEntityBuilder);
        postEntityBuilder.
                addString("makepost", "1").
                addString("legacy-posting", "1").
                addString("redirecttothread", "1");
    }
    
    @Override
    protected List<? extends NameValuePair> getReportFormAllValues(DeletePostModel model) {
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new BasicNameValuePair("board", model.boardName));
        pairs.add(new BasicNameValuePair("post[]", model.postNumber));
        pairs.add(new BasicNameValuePair("reportpost", "Пожаловаться"));
        return pairs;
    }
    
    @Override
    protected String getDeleteFormValue(DeletePostModel model) {
        return "Удалить";
    }
    
    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(getChanName())) throw new IllegalArgumentException("wrong chan");
        if (model.type == UrlPageModel.TYPE_CATALOGPAGE) return getUsingUrl() + model.boardName + "/catalog.html";
        return WakabaUtils.buildUrl(model, getUsingUrl());
    }
    
    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        String urlPath = UrlPathUtils.getUrlPath(url, getAllDomains());
        if (urlPath == null) throw new IllegalArgumentException("wrong domain");
        int catalogIndex = url.indexOf("/catalog.html");
        if (catalogIndex > 0) {
            try {
                String path = url.substring(0, catalogIndex);
                UrlPageModel model = new UrlPageModel();
                model.chanName = getChanName();
                model.type = UrlPageModel.TYPE_CATALOGPAGE;
                model.boardName = path.substring(path.lastIndexOf('/') + 1);
                model.catalogType = 0;
                return model;
            } catch (Exception e) {}
        }
        return WakabaUtils.parseUrlPath(urlPath, getChanName());
    }
    
    @Override
    public String fixRelativeUrl(String url) {
        if (url.startsWith("//")) return (useHttps() ? "https:" : "http:") + url;
        return super.fixRelativeUrl(url);
    }
    
    @SuppressLint("SimpleDateFormat")
    protected static class Instant0chanReader extends KusabaReader {
        private static final char[] FILTER_POST_NUMBER = "data-id=\"".toCharArray();
        private static final char[] FILTER_ATTACHMENT_OPEN = "<figcaption class=\"filesize\">".toCharArray();
        private static final char[] FILTER_ATTACHMENT_CLOSE = "</figcaption>".toCharArray();
        private static final char[] FILTER_EMBEDDED_OPEN = "<figure class=\"multiembed video-embed\"".toCharArray();
        private static final char[] FILTER_EMBEDDED_CLOSE = "</figure>".toCharArray();
        private static final char[] FILTER_THREAD_STATUS = "class=\"posthead ".toCharArray();
        
        private static final Pattern PATTERN_EMBEDDED_URL =
                Pattern.compile("<a.+?href=\"(.+?)\".*?>(.+?)?</a>", Pattern.DOTALL);
        private static final Pattern PATTERN_EMBEDDED_THUMBNAIL =
                Pattern.compile("<img class=\"embed-thumbnail\".+?src=\"(.+?)\"", Pattern.DOTALL);
        private static final DateFormat DATE_FORMAT;
        private static final DateFormat DATE_FORMAT_OLD;
        static {
            DateFormatSymbols symbols = new DateFormatSymbols();
            symbols.setShortMonths(new String[] {
                    "Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"
            });
            DATE_FORMAT = new SimpleDateFormat("yyyy MMM dd HH:mm:ss", symbols) {
                @Override
                public Date parse(String date) throws ParseException {
                    return super.parse(date.replaceAll("(?:\\D+)(\\d.+)", "$1"));
                }
            };
            DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT+3"));

            DATE_FORMAT_OLD = new SimpleDateFormat("yy/MM/dd(EEE)HH:mm", Locale.US);
            DATE_FORMAT_OLD.setTimeZone(TimeZone.getTimeZone("GMT+3"));
        }
        
        private int curNumberPos = 0;
        private int curAttachmentPos = 0;
        private int curEmbedPos = 0;
        private int curThreadStatusPos = 0;
        
        public Instant0chanReader(InputStream in, DateFormat dateFormat, boolean canCloudflare) {
            super(in, dateFormat, canCloudflare, ~FLAG_HANDLE_EMBEDDED_POST_POSTPROCESS);
        }
        
        public Instant0chanReader(InputStream in, boolean canCloudflare) {
            this(in, DATE_FORMAT, canCloudflare);
        }
        
        @Override
        protected void customFilters(int ch) throws IOException {
            super.customFilters(ch);
            
            if (ch == FILTER_POST_NUMBER[curNumberPos]) {
                ++curNumberPos;
                if (curNumberPos == FILTER_POST_NUMBER.length) {
                    currentPost.number = readUntilSequence("\"".toCharArray());
                    curNumberPos = 0;
                }
            } else {
                if (curNumberPos != 0) curNumberPos = ch == FILTER_POST_NUMBER[0] ? 1 : 0;
            }

            if (ch == FILTER_THREAD_STATUS[curThreadStatusPos]) {
                ++curThreadStatusPos;
                if (curThreadStatusPos == FILTER_THREAD_STATUS.length) {
                    String status = readUntilSequence("\"".toCharArray());
                    if (status.contains("locked")) currentThread.isClosed = true;
                    if (status.contains("stickied")) currentThread.isSticky = true;
                    curThreadStatusPos = 0;
                }
            } else {
                if (curThreadStatusPos != 0) curThreadStatusPos = ch == FILTER_THREAD_STATUS[0] ? 1 : 0;
            }

            if (ch == FILTER_ATTACHMENT_OPEN[curAttachmentPos]) {
                ++curAttachmentPos;
                if (curAttachmentPos == FILTER_ATTACHMENT_OPEN.length) {
                    parseAttachment(readUntilSequence(FILTER_ATTACHMENT_CLOSE));
                    curAttachmentPos = 0;
                }
            } else {
                if (curAttachmentPos != 0) curAttachmentPos = ch == FILTER_ATTACHMENT_OPEN[0] ? 1 : 0;
            }
            
            if (ch == FILTER_EMBEDDED_OPEN[curEmbedPos]) {
                ++curEmbedPos;
                if (curEmbedPos == FILTER_EMBEDDED_OPEN.length) {
                    parseEmbedded(readUntilSequence(FILTER_EMBEDDED_CLOSE));
                    curEmbedPos = 0;
                }
            } else {
                if (curEmbedPos != 0) curEmbedPos = ch == FILTER_EMBEDDED_OPEN[0] ? 1 : 0;
            }
        }
        
        @Override
        protected void parseDate(String date) {
            date = RegexUtils.removeHtmlTags(date).trim();
            if (date.length() > 0) {
                try {
                    currentPost.timestamp = dateFormat.parse(date).getTime();
                } catch (Exception e) {
                    try {
                        currentPost.timestamp = DATE_FORMAT_OLD.parse(date).getTime();
                    } catch (Exception e2) {
                        Logger.e(TAG, "cannot parse date; make sure you choose the right DateFormat for this chan", e);
                    }
                }
            }
        }
        
        protected void parseEmbedded(String data) {
            Matcher matcher = PATTERN_EMBEDDED_URL.matcher(data);
            if (matcher.find()) {
                AttachmentModel attachment = new AttachmentModel();
                attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                attachment.size = -1;
                attachment.path = matcher.group(1);
                attachment.originalName = matcher.group(2);
                Matcher thumbMatcher = PATTERN_EMBEDDED_THUMBNAIL.matcher(data);
                if (thumbMatcher.find()) {
                    attachment.thumbnail = thumbMatcher.group(1);
                }
                ++currentThread.attachmentsCount;
                currentAttachments.add(attachment);
            }
        }
        
        @Override
        protected void parseThumbnail(String imgTag) {
            if (imgTag.contains("class=\"_country_\"")) {
                int start, end;
                if ((start = imgTag.indexOf("src=\"")) != -1 && (end = imgTag.indexOf('\"', start + 5)) != -1) {
                    BadgeIconModel iconModel = new BadgeIconModel();
                    iconModel.source = imgTag.substring(start + 5, end);
                    int currentIconsCount = currentPost.icons == null ? 0 : currentPost.icons.length;
                    BadgeIconModel[] newIconsArray = new BadgeIconModel[currentIconsCount + 1];
                    for (int i=0; i<currentIconsCount; ++i) newIconsArray[i] = currentPost.icons[i];
                    newIconsArray[currentIconsCount] = iconModel;
                    currentPost.icons = newIconsArray;
                }
            } else {
                super.parseThumbnail(imgTag);
            }
        }
    }
    
}
