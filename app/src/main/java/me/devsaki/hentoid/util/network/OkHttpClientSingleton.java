package me.devsaki.hentoid.util.network;

import static me.devsaki.hentoid.util.HelperKt.assertNonUiThread;
import static me.devsaki.hentoid.util.network.HttpHelperKt.DEFAULT_REQUEST_TIMEOUT;
import static me.devsaki.hentoid.util.network.HttpHelperKt.HEADER_USER_AGENT;

import android.util.SparseArray;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import me.devsaki.hentoid.core.HentoidApp;
import me.devsaki.hentoid.util.Settings;
import okhttp3.Cache;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.brotli.BrotliInterceptor;
import okhttp3.dnsoverhttps.DnsOverHttps;
import timber.log.Timber;

/**
 * Manages a single instance of OkHttpClient per timeout delay
 */
@SuppressWarnings("squid:S3077")
// https://stackoverflow.com/questions/11639746/what-is-the-point-of-making-the-singleton-instance-volatile-while-using-double-l
public class OkHttpClientSingleton {

    private static volatile SparseArray<OkHttpClient> instance = new SparseArray<>();


    private OkHttpClientSingleton() {
    }

    public static OkHttpClient getInstance() {
        return getInstance(DEFAULT_REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, true);
    }

    public static OkHttpClient getInstance(int connectTimeout, int ioTimeout, boolean followRedirects) {
        int key = (connectTimeout * 100) + ioTimeout + (followRedirects ? 1 : 0);
        if (null == instance.get(key)) {
            synchronized (OkHttpClientSingleton.class) {
                if (null == instance.get(key)) {
                    instance.put(key, buildClient(connectTimeout, ioTimeout, followRedirects));
                }
            }
        }
        return OkHttpClientSingleton.instance.get(key);
    }

    public static void reset() {
        assertNonUiThread(); // Closing network operations shouldn't happen on the UI thread
        synchronized (OkHttpClientSingleton.class) {
            int size = instance.size();
            for (int i = 0; i < size; i++) {
                instance.valueAt(i).dispatcher().executorService().shutdown();
                instance.valueAt(i).connectionPool().evictAll();
                try {
                    Cache cache = instance.valueAt(i).cache();
                    if (cache != null) cache.close();
                } catch (IOException e) {
                    Timber.i(e);
                }
            }
            instance.clear();
        }
    }

    private static OkHttpClient buildBootstrapClient() {
        long CACHE_SIZE = 5L * 1024 * 1024; // 5 MB

        return new OkHttpClient.Builder()
                .addInterceptor(OkHttpClientSingleton::rewriteUserAgentInterceptor)
                .addInterceptor(BrotliInterceptor.INSTANCE)
                .cache(new Cache(HentoidApp.Companion.getInstance().getCacheDir(), CACHE_SIZE))
                .build();
    }

    private static OkHttpClient buildClient(int connectTimeout, int ioTimeout, boolean followRedirects) {
        if (null == instance.get(0)) {
            synchronized (OkHttpClientSingleton.class) {
                if (null == instance.get(0)) {
                    instance.put(0, buildBootstrapClient());
                }
            }
        }
        OkHttpClient primaryClient = instance.get(0);

        // Set custom delays
        OkHttpClient.Builder result = primaryClient.newBuilder()
                .followRedirects(followRedirects)
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(ioTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(ioTimeout, TimeUnit.MILLISECONDS);

        // Add DNS over HTTPS if needed
        Source doHSource = Source.Companion.fromValue(Settings.INSTANCE.getDnsOverHttps());
        if (doHSource != Source.NONE) {
            DnsOverHttps dns = new DnsOverHttps.Builder()
                    .client(primaryClient)
                    .url(doHSource.getPrimaryUrl())
                    .bootstrapDnsHosts(doHSource.getHosts())
                    .build();
            result.dns(dns);
        }

        return result.build();
    }

    @NonNull
    private static okhttp3.Response rewriteUserAgentInterceptor(Interceptor.Chain chain) throws IOException {
        Request.Builder builder = chain.request().newBuilder();
        // If not specified, all requests are done with the device's mobile user-agent, without the Hentoid string
        if (null == chain.request().header("User-Agent") && null == chain.request().header("user-agent"))
            builder.header(HEADER_USER_AGENT, HttpHelperKt.getMobileUserAgent(false, true));
        return chain.proceed(builder.build());
    }
}
