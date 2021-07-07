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

import android.app.Activity;

import java.net.URL;

import nya.miku.wishmaster.api.HttpChanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.common.MainApplication;
import nya.miku.wishmaster.http.interactive.InteractiveException;

public class HashwallExceptionAntiDDOS extends InteractiveException {
    private static final long serialVersionUID = 1L;

    private static final String SERVICE_NAME = "Hashwall";
    private static final String TAG = "HashwallException";

    private String url;
    private String baseUrl;
    private String chanName;

    public String getUrl() {
        return url;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    
    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    public static HashwallExceptionAntiDDOS newInstance(String url, String chanName) {
        try {
            HashwallExceptionAntiDDOS res = new HashwallExceptionAntiDDOS();
            res.url = url;
            res.chanName = chanName;
            try {
                URL baseUrl = new URL(url);
                res.baseUrl = baseUrl.getProtocol() + "://" + baseUrl.getHost() + "/";
            } catch (Exception e) {
                Logger.e(TAG, e);
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void handle(Activity activity, CancellableTask task, Callback callback) {
        HashwallUIHandler.handleHashwall(this, (HttpChanModule) MainApplication.getInstance().getChanModule(chanName), activity, task, callback);
    }
}
