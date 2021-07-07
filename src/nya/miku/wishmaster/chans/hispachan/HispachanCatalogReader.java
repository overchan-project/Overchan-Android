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

import android.graphics.Color;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BadgeIconModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.util.RegexUtils;

public class HispachanCatalogReader implements Closeable {
    private static final char[] CATALOG_START = "<div id=\"hc-catalog\"".toCharArray();

    private static final Pattern PATTERN_THREAD_STATS = Pattern.compile("(?:([RIV]).*?(\\d+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_ICON = Pattern.compile("<img[^>]*src=\"(.+?)\"(?:.*?title=\"(.+?)\")?");

    private static final int FILTER_START = 0;
    private static final int FILTER_THUMBNAIL = 1;
    private static final int FILTER_SUBJECT = 2;
    private static final int FILTER_POSTER_NAME = 3;
    private static final int FILTER_POSTER_ID = 4;
    private static final int FILTER_ADMIN = 5;
    private static final int FILTER_MOD = 6;
    private static final int FILTER_COMMENT = 7;
    private static final int FILTER_STATS = 8;

    public static final char[][] FILTERS_OPEN = {
        "<div class=\"hc-catalog-item".toCharArray(),
        "<img".toCharArray(),
        "<div class=\"filetitle\">".toCharArray(),
        "<span class=\"postername\">".toCharArray(),
        "<span class=\"anonid\"".toCharArray(),
        "<span class=\"admin\">".toCharArray(),
        "<span class=\"mod\">".toCharArray(),
        "<div class=\"fullop\"".toCharArray(),
        "<strong class=\"threadstats\">".toCharArray(),
    };

    private static final char[][] FILTERS_CLOSE = {
        ">".toCharArray(),
        "\"".toCharArray(),
        "</div>".toCharArray(),
        "</span>".toCharArray(),
        "</span>".toCharArray(),
        "</span>".toCharArray(),
        "</span>".toCharArray(),
        "</div>".toCharArray(),
        "</strong>".toCharArray(),
    };

    private final Reader _in;

    private StringBuilder readBuffer = new StringBuilder();
    private List<ThreadModel> threads;
    private ThreadModel currentThread;

    public HispachanCatalogReader(Reader reader) {
        _in = reader;
    }

    public HispachanCatalogReader(InputStream in) {
        this(new BufferedReader(new InputStreamReader(in)));
    }

    public ThreadModel[] readPage() throws IOException {
        threads = new ArrayList<>();
        initThreadModel();
        skipUntilSequence(CATALOG_START);
        readData();
        return threads.toArray(new ThreadModel[0]);
    }

    private void readData() throws IOException {
        int filtersCount = FILTERS_OPEN.length;
        int[] pos = new int[filtersCount];
        int[] len = new int[filtersCount];
        for (int i=0; i<filtersCount; ++i) len[i] = FILTERS_OPEN[i].length;

        int curChar;
        while ((curChar = _in.read()) != -1) {
            for (int i=0; i<filtersCount; ++i) {
                if (curChar == FILTERS_OPEN[i][pos[i]]) {
                    ++pos[i];
                    if (pos[i] == len[i]) {
                        handleFilter(i);
                        pos[i] = 0;
                    }
                } else {
                    if (pos[i] != 0) pos[i] = curChar == FILTERS_OPEN[i][0] ? 1 : 0;
                }
            }
        }
        finalizeThread();
    }

    private void initThreadModel() {
        currentThread = new ThreadModel();
        currentThread.postsCount = 0;
        currentThread.attachmentsCount = 0;
        currentThread.posts = new PostModel[1];
        currentThread.posts[0] = new PostModel();
        currentThread.posts[0].email = "";
        currentThread.posts[0].trip = "";
        currentThread.posts[0].name = "Anónimo";
    }

    private void finalizeThread() {
        if (currentThread.posts[0].number != null && currentThread.posts[0].number.length() > 0) {
            currentThread.threadNumber = currentThread.posts[0].number;
            currentThread.posts[0].parentThread = currentThread.posts[0].number;
            if (currentThread.posts[0].subject == null) currentThread.posts[0].subject = "";
            if (currentThread.posts[0].comment == null) currentThread.posts[0].comment = "";
            if (currentThread.posts[0].attachments == null) currentThread.posts[0].attachments = new AttachmentModel[0];
            if (currentThread.posts[0].subject.length() != 0
                    && currentThread.posts[0].comment.startsWith(currentThread.posts[0].subject)) {
                currentThread.posts[0].comment = currentThread.posts[0].comment.substring(currentThread.posts[0].subject.length()); 
            }
            threads.add(currentThread);
        }
        initThreadModel();
    }

    private void handleFilter(int filterIndex) throws IOException {
        switch (filterIndex) {
            case FILTER_START:
                finalizeThread();
                String threadHtml = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                int start, end;
                if ((start = threadHtml.indexOf("data-id=\"")) != -1 && (end = threadHtml.indexOf('\"', start + 9)) != -1) {
                    String threadNumber = threadHtml.substring(start + 9, end);
                    if (threadNumber.length() > 0) currentThread.posts[0].number = threadNumber;
                }
                if ((start = threadHtml.indexOf("data-ts=\"")) != -1 && (end = threadHtml.indexOf('\"', start + 9)) != -1) {
                    String timestamp = threadHtml.substring(start + 9, end);
                    if (timestamp.length() > 0) {
                        try {
                            currentThread.posts[0].timestamp = Long.parseLong(timestamp) * 1000L;
                        } catch (NumberFormatException e) {}
                    }
                }
                if ((start = threadHtml.indexOf("data-locked=\"")) != -1 && (end = threadHtml.indexOf('\"', start + 13)) != -1) {
                    String boolVal = threadHtml.substring(start + 13, end);
                    if (boolVal.equals("true")) currentThread.isClosed = true;
                }
                if ((start = threadHtml.indexOf("data-stickied=\"")) != -1 && (end = threadHtml.indexOf('\"', start + 16)) != -1) {
                    String boolVal = threadHtml.substring(start + 16, end);
                    if (boolVal.equals("true")) currentThread.isSticky = true;
                }
                break;
            case FILTER_THUMBNAIL:
                skipUntilSequence("src=\"".toCharArray());
                String thumbnail = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                if (thumbnail.contains("/thumb/")) {
                    AttachmentModel attachment = new AttachmentModel();
                    attachment.type = AttachmentModel.TYPE_IMAGE_STATIC;
                    attachment.size = -1;
                    attachment.width = -1;
                    attachment.height = -1;
                    attachment.thumbnail = thumbnail;
                    attachment.path = thumbnail.replace("/thumb/", "/src/").replaceFirst("(\\d+)s\\.", "$1.");
                    currentThread.posts[0].attachments = new AttachmentModel[] { attachment };
                } else {
                    if (thumbnail.contains("/css/sticky")) currentThread.isSticky = true;
                    if (thumbnail.contains("/css/locked")) currentThread.isClosed = true;
                }
                break;
            case FILTER_SUBJECT:
                String subjectHtml = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                currentThread.posts[0].subject = StringEscapeUtils.unescapeHtml4(RegexUtils.removeHtmlTags(subjectHtml)).trim();
                break;
            case FILTER_POSTER_NAME:
                String posterHtml = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                Matcher iconMatcher = PATTERN_ICON.matcher(posterHtml);
                if (iconMatcher.find()) {
                    BadgeIconModel iconModel = new BadgeIconModel();
                    iconModel.source = iconMatcher.group(1);
                    iconModel.description = StringEscapeUtils.unescapeHtml4(iconMatcher.group(2));
                    currentThread.posts[0].icons = new BadgeIconModel[] { iconModel };
                }
                currentThread.posts[0].name = StringEscapeUtils.unescapeHtml4(RegexUtils.removeHtmlTags(posterHtml)).
                        replace("\u00a0", " ").trim();
                break;
            case FILTER_POSTER_ID:
                String colorInfo = readUntilSequence(">".toCharArray());
                String id = readUntilSequence(FILTERS_CLOSE[filterIndex]).substring(1);
                if (id.length() > 0) {
                    currentThread.posts[0].name += " ID:" + id;
                    int index, endIndex;
                    if ((index = colorInfo.indexOf("background-color:")) != -1
                            && (endIndex = colorInfo.indexOf('\"', index + 17)) != -1) {
                        String color = colorInfo.substring(index + 17, endIndex).trim();
                        try {
                            currentThread.posts[0].color = Color.parseColor(color);
                        } catch (Exception e) {}
                    }
                }
                break;
            case FILTER_ADMIN:
            case FILTER_MOD:
                currentThread.posts[0].trip += readUntilSequence(FILTERS_CLOSE[filterIndex]).trim();
                break;
            case FILTER_COMMENT:
                skipUntilSequence(">".toCharArray());
                currentThread.posts[0].comment = readUntilSequence(FILTERS_CLOSE[filterIndex]);//.replace("\">", ">");
                break;
            case FILTER_STATS:
                String stats = readUntilSequence(FILTERS_CLOSE[filterIndex]);
                Matcher matcher = PATTERN_THREAD_STATS.matcher(RegexUtils.removeHtmlTags(stats));
                while (matcher.find()) {
                    int count = Integer.parseInt(matcher.group(2));
                    switch (matcher.group(1).toUpperCase()) {
                        case "R":
                            currentThread.postsCount = ++count;
                            break;
                        case "I":
                        case "V":
                            currentThread.attachmentsCount += count;
                            break;
                    }
                }
                break;
        }
    }

    private void skipUntilSequence(char[] sequence) throws IOException {
        int len = sequence.length;
        if (len == 0) return;
        int pos = 0;
        int curChar;
        while ((curChar = _in.read()) != -1) {
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
        while ((curChar = _in.read()) != -1) {
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

    @Override
    public void close() throws IOException {
        _in.close();
    }
}
