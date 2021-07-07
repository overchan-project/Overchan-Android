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

package nya.miku.wishmaster.http.hashwall;

import nya.miku.wishmaster.api.util.CryptoUtils;

public class HashwallChallengeSolver {
    private static final int HASH_LENGTH = 40;

    public static String bruteForceHash(String sha1Hash, String passPhrase, int upperBound) {
        if (sha1Hash.length() == HASH_LENGTH) {
            sha1Hash = sha1Hash.toLowerCase();
            for (int salt = 0; salt < upperBound; salt++) {
                if (sha1Hash.equals(CryptoUtils.computeSHA1(passPhrase + salt))) {
                    return Integer.toString(salt);
                }
            }
        }
        return null;
    }
}
