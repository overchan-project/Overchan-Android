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

package nya.miku.wishmaster.chans.cirno;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.util.CryptoUtils;
import nya.miku.wishmaster.api.util.WakabaReader;

/**
 * Парсер страниц 410chan.org, корректно определяется сажа (символ ⇩ в теме сообщения)
 * @author miku-nyan
 *
 */
public class Chan410Reader extends WakabaReader {
    
    private static final Pattern SPAN_ADMIN_PATTERN = Pattern.compile("<span class=\"admin\">(.*?)</span>(.*?)", Pattern.DOTALL);
    private static final char[] END_THREAD_FILTER = "<div id=\"thread".toCharArray();
    private static final char[] BADGE_FILTER = "class=\"post-badge".toCharArray();
    private static final char[] USER_ID_FILTER = " ID:".toCharArray();
    private int curPos = 0;
    private int badgePos = 0;
    private int idPos = 0;
    
    public Chan410Reader(InputStream in) {
        super(in, DateFormats.CHAN_410_DATE_FORMAT);
    }
    
    public Chan410Reader(InputStream in, DateFormat dateFormat) {
        super(in, dateFormat);
    }
    
    @Override
    protected void customFilters(int ch) throws IOException {
        if (ch == END_THREAD_FILTER[curPos]) {
            ++curPos;
            if (curPos == END_THREAD_FILTER.length) {
                skipUntilSequence(">".toCharArray());
                finalizeThread();
                curPos = 0;
            }
        } else {
            if (curPos != 0) curPos = ch == END_THREAD_FILTER[0] ? 1 : 0;
        }
        
        if (ch == BADGE_FILTER[badgePos]) {
            ++badgePos;
            if (badgePos == BADGE_FILTER.length) {
                String threadBadge = readUntilSequence("\"".toCharArray());
                if (threadBadge.contains("locked")) currentThread.isClosed = true;
                if (threadBadge.contains("sticky")) currentThread.isSticky = true;
                badgePos = 0;
            }
        } else {
            if (badgePos != 0) badgePos = ch == BADGE_FILTER[0] ? 1 : 0;
        }
        
        if (ch == USER_ID_FILTER[idPos]) {
            ++idPos;
            if (idPos == USER_ID_FILTER.length) {
                String id = readUntilSequence("<".toCharArray()).trim();
                if (id.length() == 6) {
                    currentPost.name += " ID: " + id;
                    currentPost.color = CryptoUtils.hashIdColor(id);
                }
                idPos = 0;
            }
        } else {
            if (idPos != 0) idPos = ch == USER_ID_FILTER[0] ? 1 : 0;
        }
    }

    @Override
    protected void parseAttachment(String html) {
        super.parseAttachment(html);
        if (currentAttachments.size() > 0) {
            AttachmentModel attachment = currentAttachments.get(currentAttachments.size() - 1);
            if (attachment.type == AttachmentModel.TYPE_OTHER_FILE && attachment.path.endsWith(".webp")) {
                attachment.type = AttachmentModel.TYPE_IMAGE_STATIC;
            }
        }
    }

    @Override
    protected void postprocessPost(PostModel post) {
        if (post.subject.contains("\u21E9")) post.sage = true;
    }
    
    @Override
    protected void parseDate(String date) {
        super.parseDate(date);
        if (currentPost.timestamp == 0) {
            Matcher matcher = SPAN_ADMIN_PATTERN.matcher(date);
            if (matcher.matches()) {
                currentPost.trip = (currentPost.trip == null ? "" : currentPost.trip) + StringEscapeUtils.unescapeHtml4(matcher.group(1).trim());
                super.parseDate(matcher.group(2));
            }
        }
    }
}
