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

import android.annotation.SuppressLint;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

@SuppressLint("SimpleDateFormat")
public class HaibaneConstants {
    static final String CHAN_NAME = "haibane.ru";
    static final String DISPLAYING_NAME = "Хайбане.ru";
    static final String DEFAULT_DOMAIN = "haibane.ru";
    static final String ONION_DOMAIN = "haibanej33s4gfts.onion";
    static final String[] DOMAINS = new String[] { DEFAULT_DOMAIN, ONION_DOMAIN };
    static final String PREF_KEY_USE_ONION = "PREF_KEY_USE_ONION";

    static final DateFormat CHAN_DATEFORMAT;
    static {
        CHAN_DATEFORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        CHAN_DATEFORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    static final List<String> CATEGORIES = Arrays.asList("Всё вподряд", "Убежище", "Хайбане.ru");

    static final Pattern THREADPAGE_PATTERN = Pattern.compile("([^/]+)/(\\d+)(?:\\D*#(\\d+))?");
    static final Pattern BOARDPAGE_PATTERN = Pattern.compile("([^/]+)(?:/page/(\\d+))?");
    static final Pattern CATALOGPAGE_PATTERN = Pattern.compile("catalog/([a-z0-9]+)");

    static final Pattern ATTACHMENT_SIZE_PATTERN = Pattern.compile("([\\d,.]+)\\s?([km])?b", Pattern.CASE_INSENSITIVE);
    static final Pattern INTERNAL_LINK_PATTERN = Pattern.compile("<a.*?data-post-local-id=(\\d+).*?href=['\"]([^'\"]+)#.*?>(.*?)</a>");
    static final Pattern ERROR_PATTERN = Pattern.compile("<div id=\"message\"[^>]*>(.*?)</div>");

    static final String[] RATINGS = new String[] { "SFW", "R15", "R18", "R18G" };
    static final String SESSION_COOKIE_NAME = "_SESSION";
}
