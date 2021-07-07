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

import org.apache.commons.lang3.StringEscapeUtils;

import java.text.ParseException;
import java.util.regex.Matcher;

import nya.miku.wishmaster.api.models.AttachmentModel;
import nya.miku.wishmaster.api.models.BoardModel;
import nya.miku.wishmaster.api.models.PostModel;
import nya.miku.wishmaster.api.models.ThreadModel;
import nya.miku.wishmaster.api.util.RegexUtils;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONObject;

import static nya.miku.wishmaster.chans.monaba.HaibaneConstants.*;

public class MonabaJsonMapper {
    private static final String TAG = "MonabaJsonMapper";
    
    static BoardModel defaultBoardModel(String boardName) {
        BoardModel board = new BoardModel();
        board.chan = CHAN_NAME;
        board.boardName = boardName;
        board.boardDescription = boardName;
        board.timeZoneId = "GMT+3";
        board.defaultUserName = "Anonymous";
        board.readonlyBoard = false;
        board.requiredFileForNewThread = true;
        board.allowDeletePosts = false;
        board.allowDeleteFiles = false;
        board.allowReport = BoardModel.REPORT_NOT_ALLOWED;
        board.allowNames = true;
        board.allowSubjects = true;
        board.allowSage = true;
        board.allowEmails = false;
        board.ignoreEmailIfSage = true;
        board.allowCustomMark = false;
        board.allowRandomHash = true;
        board.allowIcons = true;
        board.iconDescriptions = RATINGS;
        board.attachmentsMaxCount = 10;
        board.attachmentsFormatFilters = null;
        board.uniqueAttachmentNames = false;
        board.markType = BoardModel.MARK_BBCODE;
        board.firstPage = 0;
        board.lastPage = BoardModel.LAST_PAGE_UNDEFINED;
        board.catalogAllowed = true;
        board.allowDeletePosts = true;
        board.allowDeleteFiles = true;
        board.bumpLimit = 500;
        return board;
    }

    static BoardModel mapBoardModel(JSONObject json, String shortName) throws Exception {
        BoardModel model = MonabaJsonMapper.defaultBoardModel(shortName);
        model.boardDescription = json.optString("title", model.boardName);
        model.boardCategory = json.optString("category", "").trim();
        model.defaultUserName = json.optString("defaultName");
        model.bumpLimit = json.optInt("bumpLimit", 500);
        if (model.bumpLimit == 0) model.bumpLimit = Integer.MAX_VALUE;
        model.allowNames = !json.optBoolean("enableForcedAnon", false);
        model.requiredFileForNewThread = json.optString("opFile").equalsIgnoreCase("Required");
        int maxFiles = json.optInt("numberFiles", -1);
        if (maxFiles > -1) model.attachmentsMaxCount = maxFiles;
        try {
            JSONArray fileTypes = json.getJSONArray("allowedTypes");
            if (fileTypes != null && fileTypes.length() > 0) {
                String[] formats = new String[fileTypes.length()];
                for (int i = 0; i < formats.length; i++) {
                    formats[i] = fileTypes.getString(i);
                }
                model.attachmentsFormatFilters = formats;
            }
        } catch (Exception e) {}
        return model;
    }

    static ThreadModel mapThreadModel(JSONObject source) throws Exception {
        JSONObject op = source.getJSONObject("op");
        JSONArray replies = source.optJSONArray("replies");
        ThreadModel model = new ThreadModel();
        model.posts = new PostModel[1 + (replies != null ? replies.length() : 0)];
        model.posts[0] = mapPostModel(op);
        if (replies != null) {
            for (int i = 0, rlen = replies.length(); i < rlen; ++i) {
                model.posts[i + 1] = mapPostModel(replies.getJSONObject(i));
            }
        }
        model.threadNumber = model.posts[0].number;
        model.postsCount = model.posts.length;
        int omitted = source.optInt("omitted", -1);
        if (omitted > 0) model.postsCount += omitted;
        model.isSticky = op.optJSONObject("post").optBoolean("sticked");
        model.isClosed = op.optJSONObject("post").optBoolean("locked");
        return model;
    }

    private static PostModel mapPostText(JSONObject textData) {
        PostModel model = new PostModel();
        model.number = Integer.toString(textData.getInt("id"));
        model.name = StringEscapeUtils.unescapeHtml4(textData.optString("name")).trim();
        model.subject = StringEscapeUtils.unescapeHtml4(textData.optString("title")).trim();
        model.comment = textData.optString("message", textData.optString("rawMessage"));
        model.comment = RegexUtils.replaceAll(model.comment, INTERNAL_LINK_PATTERN, "<a href=\"$2#$1\">$3</a>");
        model.parentThread = textData.optString("parent", "0");
        if (model.parentThread.equals("0")) model.parentThread = model.number;
        try {
            model.timestamp = CHAN_DATEFORMAT.parse(textData.optString("date")).getTime();
        } catch (ParseException e) {
            Logger.e(TAG, "cannot parse date; make sure you choose the right DateFormat for this chan", e);
        }
        return model;
    }

    static PostModel mapPostModel(JSONObject source) {
        PostModel model = mapPostText(source.optJSONObject("post"));
        JSONArray files = source.optJSONArray("files");
        if (files != null && files.length() > 0) {
            model.attachments = new AttachmentModel[files.length()];
            for (int i = 0, len = files.length(); i < len; ++i) {
                model.attachments[i] = mapAttachmentModel(files.optJSONObject(i));
            }
        }
        return model;
    }

    static ThreadModel mapCatalogThreadModel(JSONArray source) {
        JSONObject opPost = source.getJSONObject(0);
        PostModel postModel = mapPostText(opPost);
        try {
            AttachmentModel attachmentModel = mapAttachmentModel(source.getJSONArray(1).getJSONObject(0));
            postModel.attachments = new AttachmentModel[] { attachmentModel };
        } catch (Exception e) { /* No attachments in OP */ }
        ThreadModel result = new ThreadModel();
        result.threadNumber = postModel.number;
        result.posts = new PostModel[] { postModel };
        result.postsCount = source.optInt(2, -2) + 1;
        result.isSticky = opPost.optBoolean("sticked");
        result.isClosed = opPost.optBoolean("locked");
        return result;
    }

    static AttachmentModel mapAttachmentModel(JSONObject source) {
        AttachmentModel model = new AttachmentModel();
        model.thumbnail = source.optString("thumb_path");
        model.path = source.optString("path");
        try {
            String info = source.optString("info");
            model.width = Integer.parseInt(info.split("x")[0]);
            model.height = Integer.parseInt(info.split("x")[1]);
        } catch (Exception e) {
            model.width = -1;
            model.height = -1;
        }
        Matcher byteSizeMatcher = ATTACHMENT_SIZE_PATTERN.matcher(source.optString("size"));
        if (byteSizeMatcher.find()) {
            try {
                String digits = byteSizeMatcher.group(1);
                int multiplier = 1;
                String prefix = byteSizeMatcher.group(2);
                if (prefix != null) {
                    if (prefix.equalsIgnoreCase("k")) multiplier = 1024;
                    else if (prefix.equalsIgnoreCase("m")) multiplier = 1024 * 1024;
                }
                int value = Math.round(Float.parseFloat(digits) / 1024 * multiplier);
                model.size = value > 0 ? value : -1;
            } catch (NumberFormatException e) {}
        }
        String ext = source.optString("extension");
        switch (ext) {
            case "jpeg":
            case "jpg":
            case "png":
                model.type = AttachmentModel.TYPE_IMAGE_STATIC;
                break;
            case "gif":
                model.type = AttachmentModel.TYPE_IMAGE_GIF;
                break;
            case "svg":
            case "svgz":
                model.type = AttachmentModel.TYPE_IMAGE_SVG;
                break;
            case "mp3":
            case "ogg":
            case "flac":
                model.type = AttachmentModel.TYPE_AUDIO;
                break;
            case "webm":
            case "mp4":
            case "ogv":
                model.type = AttachmentModel.TYPE_VIDEO;
                break;
            default:
                model.type = AttachmentModel.TYPE_OTHER_FILE;
        }
        return model;
    }

}
