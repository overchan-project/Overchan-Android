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

import cz.msebera.android.httpclient.cookie.Cookie;
import nya.miku.wishmaster.R;
import nya.miku.wishmaster.api.HttpChanModule;
import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.http.interactive.InteractiveException;


public class HashwallUIHandler {
    private HashwallUIHandler() {}
        
    /**
     * Обработать исключение-запрос проверки Hashwall.
     * Вызывать из фонового потока
     * @param e исключение {@link HashwallExceptionAntiDDOS}
     * @param chan модуль чана
     * @param activity активность, в которой будет создан диалог (в случае проверки с капчей),
     * или в контексте которой будет создан WebView для Anti DDOS проверки с javascript.
     * Используется как доступ к UI потоку ({@link Activity#runOnUiThread(Runnable)})
     * @param task отменяемая задача
     * @param callback интерфейс {@link InteractiveException.Callback}
     */
    static void handleHashwall(final HashwallExceptionAntiDDOS e, final HttpChanModule chan, final Activity activity, final CancellableTask task,
                                final InteractiveException.Callback callback) {
        if (task.isCancelled()) return;

        if (!HashwallChecker.getInstance().isAvaibleAntiDDOS()) {
            //если анти ддос проверка уже проводится другим потоком, тогда подождем завершения и объявим успех
            //в случае, если проверка была по тому же ChanModule, проверка уже будет пройдена
            //в противном случае на следующей попытке (закачки) сайт выкинет исключение снова
            //и мы сможем обработать исключение для этого чана на свободном HashwallChecker
            while (!HashwallChecker.getInstance().isAvaibleAntiDDOS()) Thread.yield();
            if (!task.isCancelled()) activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess();
                }
            });
            return;
        }
        Cookie hwCookie = HashwallChecker.getInstance().checkAntiDDOS(e,
                chan.getHttpClient(), task, activity);
        if (hwCookie != null) {
            chan.saveCookie(hwCookie);
            if (!task.isCancelled()) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onSuccess();
                    }
                });
            }
        } else if (!task.isCancelled()) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    callback.onError(activity.getString(R.string.error_cloudflare_antiddos));
                }
            });
        }
    }
}
