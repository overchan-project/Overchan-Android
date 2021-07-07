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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;

import nya.miku.wishmaster.api.models.SimpleBoardModel;
import nya.miku.wishmaster.api.util.RegexUtils;

public class HispachanBoardsListReader implements Closeable {
    private final Reader _in;
    private final StringBuilder readBuffer = new StringBuilder();
    private String currentCategory;
    private List<SimpleBoardModel> boards;
    private boolean afterCategoryName, nsfwCategory;

    private static final String BOARD_TAG = "<a rel=\"board\"";
    private static final int FILTER_CATEGORY = 0;
    private static final int FILTER_BOARD = 1;
    private static final int FILTER_NSFW = 2;
    private static final char[][] FILTERS = {
            "<b>".toCharArray(),
            BOARD_TAG.toCharArray(),
            "NSFW".toCharArray()
    };
    private static final char[] B_CLOSE = "</b>".toCharArray();
    private static final char[] A_CLOSE = "</a>".toCharArray();

    private static final Pattern BOARD_PATTERN = Pattern.compile("href=\"/([a-z]+)/\"(?:[^>]*)>([^<]*)");

    public HispachanBoardsListReader(Reader reader) {
        _in = reader;
    }

    public HispachanBoardsListReader(InputStream in) {
        this(new BufferedReader(new InputStreamReader(in)));
    }

    public SimpleBoardModel[] readBoardsList() throws IOException {
        boards = new ArrayList<>();

        int filtersCount = FILTERS.length;
        int[] pos = new int[filtersCount];
        int[] len = new int[filtersCount];
        for (int i=0; i<filtersCount; ++i) len[i] = FILTERS[i].length;

        int curChar;
        while ((curChar = _in.read()) != -1) {
            for (int i=0; i<filtersCount; ++i) {
                if (curChar == FILTERS[i][pos[i]]) {
                    ++pos[i];
                    if (pos[i] == len[i]) {
                        handleFilter(i);
                        pos[i] = 0;
                    }
                } else {
                    if (pos[i] != 0) pos[i] = curChar == FILTERS[i][0] ? 1 : 0;
                }
            }
        }
        return boards.toArray(new SimpleBoardModel[0]);
    }

    private void handleFilter(int filter) throws IOException {
        switch (filter) {
            case FILTER_CATEGORY:
                String input = readUntilSequence(B_CLOSE);
                if (!input.contains(BOARD_TAG)) {
                    if (!input.contains("INFORMACIÓN")) {
                        currentCategory = StringEscapeUtils.unescapeHtml4(input);
                        afterCategoryName = true;
                        nsfwCategory = false;
                    }
                } else {
                    parseBoardString(input);
                }
                break;
            case FILTER_BOARD:
                parseBoardString(readUntilSequence(A_CLOSE));
                break;
            case FILTER_NSFW:
                if (afterCategoryName) {
                    afterCategoryName = false;
                    nsfwCategory = true;
                } else if (!boards.isEmpty()) { 
                    boards.get(boards.size()-1).nsfw = true;
                }
                break;
        }
    }

    private void parseBoardString(String htmlInput) {
        Matcher boardMatcher = BOARD_PATTERN.matcher(htmlInput);
        if (boardMatcher.find()) {
            afterCategoryName = false;
            SimpleBoardModel model = new SimpleBoardModel();
            model.chan = HispachanModule.CHAN_NAME;
            model.boardName = boardMatcher.group(1);
            model.boardDescription = StringEscapeUtils.unescapeHtml4(
                    RegexUtils.removeHtmlTags(boardMatcher.group(2)))
                    .replace("\u2022", "")
                    .replace("\u00a0", " ")
                    .trim();
            model.boardCategory = currentCategory;
            model.nsfw = nsfwCategory;
            boards.add(model);
            afterCategoryName = false;
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
