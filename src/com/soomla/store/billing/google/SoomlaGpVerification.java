package com.soomla.store.billing.google;

import android.os.AsyncTask;
import android.text.TextUtils;

import com.soomla.BusProvider;
import com.soomla.SoomlaUtils;
import com.soomla.store.billing.IabPurchase;
import com.soomla.store.domain.PurchasableVirtualItem;
import com.soomla.store.events.MarketPurchaseVerificationEvent;
import com.soomla.store.events.UnexpectedStoreErrorEvent;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Text;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * @author vedi
 *         date 26/05/15
 */
public class SoomlaGpVerification {

    private static final String VERIFY_URL = "https://verify.soom.la/verify_android";
    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/token";
    private static final String TAG = "SOOMLA SoomlaGpVerification";

    private final IabPurchase purchase;
    private final PurchasableVirtualItem pvi;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private boolean repeat = false;
    private boolean repeated = false;
    private String errorMessage;

    public SoomlaGpVerification(IabPurchase purchase, PurchasableVirtualItem pvi, String clientId, String clientSecret, String refreshToken) {

        if (purchase == null || pvi == null || TextUtils.isEmpty(clientId) || TextUtils.isEmpty(clientSecret) || TextUtils.isEmpty(refreshToken)) {
            SoomlaUtils.LogError(TAG, "Can't initialize SoomlaGpVerification. Missing params.");
            throw new IllegalArgumentException();
        }

        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;


        this.purchase = purchase;
        this.pvi = pvi;
    }

    private boolean verifyPurchase() {
        String accessToken = GooglePlayIabService.getInstance().getAccessToken();
        // access token has not been defined yet, force to get it
        if (TextUtils.isEmpty(accessToken)) {
            repeat = true;
            return false;
        }

        String purchaseToken = this.purchase.getToken();

        if (purchaseToken != null) {
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("purchaseToken", purchaseToken);
                jsonObject.put("packageName", this.purchase.getPackageName());
                jsonObject.put("productId", this.purchase.getSku());
                jsonObject.put("accessToken", accessToken);
                SoomlaUtils.LogDebug(TAG, String.format("verifying purchase on server: %s", VERIFY_URL));

                HttpResponse resp = doVerifyPost(jsonObject);

                if (resp == null) {
                    fireError("Failed to connect to verification server. Not doing anything ... the purchasing process will happen again next time the service is initialized.");
                    return true;
                }

                int statusCode = resp.getStatusLine().getStatusCode();

                StringBuilder stringBuilder = new StringBuilder();
                InputStream inputStream = resp.getEntity().getContent();
                Reader reader = new BufferedReader(new InputStreamReader(inputStream));
                final char[] buffer = new char[1024];
                int bytesRead;
                while ((bytesRead = reader.read(buffer, 0, buffer.length)) > 0) {
                    stringBuilder.append(buffer, 0, bytesRead);
                }
                JSONObject resultJsonObject = new JSONObject(stringBuilder.toString());
                if (statusCode < 200 || statusCode > 299) {
                    if ("Invalid Credentials".equals(resultJsonObject.optString("error"))) {
                        this.repeat = true;
                    } else {
                        fireError("There was a problem when verifying. Will try again later.");
                    }
                    return true;
                }
                return resultJsonObject.optBoolean("verified", false);

            } catch (JSONException e) {
                fireError("Cannot build up json for verification: " + e);
                return true;
            } catch (Exception e) {
                fireError(e.getMessage());
                return true;
            }
        } else {
            fireError("An error occurred while trying to get receipt purchaseToken. Stopping the purchasing process for: " + purchase.getSku());
            return true;
        }
    }

    private HttpResponse doVerifyPost(JSONObject jsonObject) throws Exception {
        HttpClient client = new DefaultHttpClient();
        HttpPost post = new HttpPost(VERIFY_URL);
        post.setHeader("Content-type", "application/json");

        String body = jsonObject.toString();
        post.setEntity(new StringEntity(body, "UTF8"));

        return client.execute(post);
    }

    private void fireError(String message) {
        SoomlaUtils.LogError(TAG, message);
        errorMessage = message;
    }

    public void verifyPurchaseAsync() {
        runAsync(new Runnable() {
            @Override
            public void run() {
                boolean result = verifyPurchase();
                if (repeat && !repeated) {   // we repeat just once
                    if (refreshToken()) {
                        repeated = true;
                        verifyPurchaseAsync();
                        return;
                    }
                }

                if (result) {
                    // I did this according, how we have this in iOS, however `verified` will be always `true` in the event.
                    BusProvider.getInstance().post(new MarketPurchaseVerificationEvent(pvi, true, purchase));
                } else {
                    BusProvider.getInstance().post(new UnexpectedStoreErrorEvent(errorMessage));
                }
            }
        });
    }

    private boolean refreshToken() {
        try {
            HttpClient client = new DefaultHttpClient();
            HttpPost post = new HttpPost(GOOGLE_AUTH_URL);

            List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
            urlParameters.add(new BasicNameValuePair("grant_type", "refresh_token"));
            urlParameters.add(new BasicNameValuePair("client_id", clientId));
            urlParameters.add(new BasicNameValuePair("client_secret", clientSecret));
            urlParameters.add(new BasicNameValuePair("refresh_token", refreshToken));

            post.setEntity(new UrlEncodedFormEntity(urlParameters));

            HttpResponse resp = client.execute(post);

            if (resp == null) {
                fireError("Failed to connect to google server.");
                return false;
            }

            StringBuilder stringBuilder = new StringBuilder();
            InputStream inputStream = resp.getEntity().getContent();
            Reader reader = new BufferedReader(new InputStreamReader(inputStream));
            final char[] buffer = new char[1024];
            int bytesRead;
            while ((bytesRead = reader.read(buffer, 0, buffer.length)) > 0) {
                stringBuilder.append(buffer, 0, bytesRead);
            }
            JSONObject resultJsonObject = new JSONObject(stringBuilder.toString());

            int statusCode = resp.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode > 299) {
                fireError("There was a problem when verifying. Will try again later.");
                return false;
            }

            String accessToken = resultJsonObject.optString("access_token");
            if (accessToken != null) {
                GooglePlayIabService.getInstance().setAccessToken(accessToken);
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

    }

    private static void runAsync(final Runnable runnable) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                runnable.run();
                return null;
            }
        }.execute(null, null);
    }
}
