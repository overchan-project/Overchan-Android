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

package nya.miku.wishmaster.chans.tohnochan;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.AbstractKusabaModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.SendPostModel;
import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.api.util.ChanModels;
import nya.miku.wishmaster.api.util.WakabaReader;
import nya.miku.wishmaster.api.util.WakabaUtils;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.http.ExtendedMultipartBuilder;
import nya.miku.wishmaster.http.streamer.HttpRequestModel;
import nya.miku.wishmaster.http.streamer.HttpResponseModel;
import nya.miku.wishmaster.http.streamer.HttpStreamer;
import nya.miku.wishmaster.http.streamer.HttpWrongStatusCodeException;

public class TohnoChanModule extends AbstractKusabaModule {
    private static final String CHAN_NAME = "tohno-chan.com";
    private static final String CHAN_DOMAIN = "tohno-chan.com";
    private static final SimpleBoardModel[] BOARDS = new SimpleBoardModel[] {
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "an", "Anime", "Media/Entertainment", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ma", "Manga", "Media/Entertainment", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "vg", "Video Games", "Media/Entertainment", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "foe", "Touhou", "Media/Entertainment", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "mp3", "Music", "Media/Entertainment", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "vn", "Visual Novels", "Media/Entertainment", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "fig", "Collectibles", "Hobbies/Interests", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "navi", "Science & technology", "Hobbies/Interests", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "cr", "Creativity", "Hobbies/Interests", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "so", "Ronery", "General discussion", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "mai", "Waifu", "General discussion", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ot", "Otaku Tangents", "General discussion", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "ns", "Hentai", "Other", true),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "kf", "Kemono Friends", "Other", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "pic", "Dump", "Other", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "lol", "Funposting", "Other", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "tat", "Debates", "Other", false),
            ChanModels.obtainSimpleBoardModel(CHAN_NAME, "fb", "Feedback", "Other", false),
    };
    
    public TohnoChanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    @Override
    public String getChanName() {
        return CHAN_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "Tohno chan";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_tohnochan, null);
    }
    
    @Override
    protected String getUsingDomain() {
        return CHAN_DOMAIN;
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
    protected boolean useHttpsDefaultValue() {
        return false;
    }
    
    @Override
    protected WakabaReader getKusabaReader(InputStream stream, UrlPageModel urlModel) {
        return new TohnoChanReader(stream, canCloudflare());
    }
    
    @Override
    protected SimpleBoardModel[] getBoardsList() {
        return BOARDS;
    }
    
    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        BoardModel model = super.getBoard(shortName, listener, task);
        model.timeZoneId = "US/Pacific";
        model.requiredFileForNewThread = !shortName.equals("mt") && !shortName.equals("fb");
        model.allowCustomMark = true;
        model.customMarkDescription = "Spoiler";
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
        TohnoChanCatalogReader in = null;
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(oldList != null).build();
        try {
            responseModel = HttpStreamer.getInstance().getFromUrl(url, rqModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                in = new TohnoChanCatalogReader(responseModel.stream);
                if (task != null && task.isCancelled()) throw new Exception("interrupted");
                return in.readPage();
            } else {
                if (responseModel.notModified()) return oldList;
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
        String result = super.sendPost(model, listener, task);
        if (model.threadNumber != null) return null;
        return result;
    }
    
    @Override
    protected void setSendPostEntity(SendPostModel model, ExtendedMultipartBuilder postEntityBuilder) throws Exception {
        postEntityBuilder.
                addString("board", model.boardName).
                addString("replythread", model.threadNumber == null ? "0" : model.threadNumber).
                addString("editpost", "0").
                addString("fifseyRid", model.name).
                addString("keeciatt", model.sage ? "sage" : ((model.email != null && model.email.length() > 0) ? model.email : "noko" )).
                addString("fochBiag", model.subject).
                addString("jenmicayn", model.comment).
                addString("postpassword", model.password);
        setSendPostEntityAttachments(model, postEntityBuilder);
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
    private static class TohnoChanReader extends KusabaReader {
        private static final DateFormat DATE_FORMAT;
        static {
            DateFormatSymbols symbols = new DateFormatSymbols();
            symbols.setShortWeekdays(new String[] { "", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" });
            DATE_FORMAT = new SimpleDateFormat("MM/dd/yy(EEE)HH:mm", symbols);
            DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("US/Pacific"));
        }
        
        private static final Pattern YOUTUBE_PATTERN = Pattern.compile("data=\"(?:.*?)/v/([^&\"\\s]*)", Pattern.DOTALL);
        
        private static final char[] END_THREAD_FILTER = "<div class=\"Spacer\">".toCharArray();
        private static final char[] EMBED_FILTER = "<object type=\"application/x-shockwave-flash\"".toCharArray();
        
        public TohnoChanReader(InputStream in, boolean canCloudflare) {
            super(in, DATE_FORMAT, canCloudflare, ~(FLAG_HANDLE_EMBEDDED_POST_POSTPROCESS|FLAG_OMITTED_STRING_REMOVE_HREF));
        }
        
        private int curEndThreadFilterPos = 0;
        private int curEmbedFilterPos = 0;
        
        @Override
        protected void customFilters(int ch) throws IOException {
            super.customFilters(ch);
            
            if (ch == END_THREAD_FILTER[curEndThreadFilterPos]) {
                ++curEndThreadFilterPos;
                if (curEndThreadFilterPos == END_THREAD_FILTER.length) {
                    finalizeThread();
                    curEndThreadFilterPos = 0;
                }
            } else {
                if (curEndThreadFilterPos != 0) curEndThreadFilterPos = ch == END_THREAD_FILTER[0] ? 1 : 0;
            }
            
            if (ch == EMBED_FILTER[curEmbedFilterPos]) {
                ++curEmbedFilterPos;
                if (curEmbedFilterPos == EMBED_FILTER.length) {
                    parseEmbedded(readUntilSequence(">".toCharArray()));
                    curEmbedFilterPos = 0;
                }
            } else {
                if (curEmbedFilterPos != 0) curEmbedFilterPos = ch == EMBED_FILTER[0] ? 1 : 0;
            }
        }
        
        private void parseEmbedded(String tag) {
            Matcher matcher = YOUTUBE_PATTERN.matcher(tag);
            if (matcher.find()) {
                String id = matcher.group(1);
                AttachmentModel attachment = new AttachmentModel();
                attachment.type = AttachmentModel.TYPE_OTHER_NOTFILE;
                attachment.size = -1;
                attachment.path = "http://www.youtube.com/watch?v=" + id;
                attachment.thumbnail = "http://img.youtube.com/vi/" + id + "/default.jpg";
                currentAttachments.add(attachment);
            }
        }
    }

}
