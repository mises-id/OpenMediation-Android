// Copyright 2020 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.utils.request.network.connect;

import android.os.Build;

import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.IOUtil;
import com.openmediation.sdk.utils.request.network.Headers;
import com.openmediation.sdk.utils.request.network.Request;
import com.openmediation.sdk.utils.request.network.certificate.PublicKeyTrustManager;
import com.openmediation.sdk.utils.request.network.certificate.SSLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import static com.openmediation.sdk.utils.request.network.Headers.KEY_CONNECTION;

public final class HttpsConnection extends AbstractUrlConnection {

    private HttpsURLConnection mConnection;

    @Override
    public void cancel() throws Exception {
        if (mConnection != null) {
            InputStream inputStream = mConnection.getErrorStream();
            if (inputStream == null) {
                inputStream = mConnection.getInputStream();
            } else {
                DeveloperLog.LogD("HttpsConnection", "cancel with error" );
            }
            if (inputStream != null) {
                IOUtil.closeQuietly(inputStream);
            }

            mConnection.disconnect();
        }
    }

    @Override
    int getResponseCode() throws IOException {
        return mConnection.getResponseCode();
    }

    @Override
    public URLConnection connect(Request request) throws Exception {
        String u = request.getUrl();
        DeveloperLog.LogD("HttpsConnection", "url is : " + u);
        URL url = new URL(u);
        mConnection = (HttpsURLConnection) url.openConnection();
        mConnection.setConnectTimeout(request.getConnectTimeout());
        mConnection.setReadTimeout(request.getReadTimeout());
        mConnection.setInstanceFollowRedirects(request.isInstanceFollowRedirects());

        Request.Method method = request.getRequestMethod();
        mConnection.setRequestMethod(method.toString());
        mConnection.setDoInput(true);
        boolean isAllowBody = isAllowBody(method);
        mConnection.setDoOutput(isAllowBody);
        mConnection.setUseCaches(false);

        Headers headers = request.getHeaders();

        if (headers != null) {
            List<String> values = headers.get(KEY_CONNECTION);
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT && values != null && !values.isEmpty()) {
                headers.set(KEY_CONNECTION, values.get(0));
            }
            Map<String, String> requestHeaders = Headers.getRequestHeaders(headers);
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                mConnection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        if (request.isCheckChain()) {
            TrustManager[] tm = {new PublicKeyTrustManager()};
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, tm, new java.security.SecureRandom());
            SSLSocketFactory ssf = sslContext.getSocketFactory();
            mConnection.setSSLSocketFactory(ssf);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            SSLFactory ssf = new SSLFactory();
            mConnection.setSSLSocketFactory(ssf);
        }
        mConnection.connect();
        return mConnection;
    }
}
