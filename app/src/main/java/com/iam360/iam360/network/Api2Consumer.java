package com.iam360.iam360.network;

import android.util.Log;

import com.iam360.iam360.model.Gateway;
import com.iam360.iam360.model.NotificationTriggerData;
import com.iam360.iam360.util.Cache;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;

import java.io.IOException;

import retrofit.Call;
import retrofit.Callback;
import retrofit.GsonConverterFactory;
import retrofit.Retrofit;
import retrofit.RxJavaCallAdapterFactory;
import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2015-11-13
 */
public class Api2Consumer {
    private static final String BASE_URL = "https://mapi.dscvr.com/api/";
    private static final String BASE_URL2 = "https://mapi.dscvr.com/";

    private static final int DEFAULT_LIMIT = 5;
    public static final int PROFILE_GRID_LIMIT = 12;

    OkHttpClient client;

    Retrofit retrofit;

    Api2Endpoints service;
    Cache cache;
    String token = null;

    private boolean flag = false;
    private boolean finish = false;

    public Api2Consumer(String token, String type) {

        Timber.d("Api2Consumer");
        this.token = token;
        client = new OkHttpClient();
//        client.setConnectTimeout(10, TimeUnit.MINUTES);
//        client.setReadTimeout(10, TimeUnit.MINUTES);
        cache = Cache.open();

        client.interceptors().add(new Interceptor() {
            @Override
            public com.squareup.okhttp.Response intercept(Chain chain) throws IOException {
                Request newRequest;

                if(token!=null){// must have condition if the route uses auth token
                    Log.d("myTag","auth token add as Header " + token);
                    newRequest = chain.request().newBuilder()
                            .addHeader("User-Agent", "Retrofit-Sample-App")
                            .addHeader("Authorization", "Bearer "+token)
                            .build();
                }else{
                    newRequest = chain.request().newBuilder()
                            .addHeader("User-Agent", "Retrofit-Sample-App")
                            .build();
                }

//                Request request = chain.request();

                Timber.v(newRequest.headers().toString());
                Timber.v(newRequest.toString());

//                com.squareup.okhttp.Response response = chain.proceed(request);
//                Timber.v(response.headers().toString());
//                Timber.v(response.toString());

                return chain.proceed(newRequest);
            }
        });

        String bs_URL = BASE_URL;
        if(type.equals("triggerNotif")){
            bs_URL = BASE_URL2;
        }

        retrofit = new Retrofit.Builder()
                .baseUrl(bs_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .client(client)
                .build();

        service =  retrofit.create(Api2Endpoints.class);
    }

    public void checkStatus(Gateway.CheckStatusData data, Callback<Gateway.CheckStatusResponse> callback) {
        Call<Gateway.CheckStatusResponse> call = service.checkStatus(data);
        call.enqueue(callback);
    }

    public void requestCode(Gateway.RequestCodeData data, Callback<Gateway.RequestCodeResponse> callback) {
        Call<Gateway.RequestCodeResponse> call = service.requestCode(data);
        call.enqueue(callback);
    }

    public void useCode(Gateway.UseCodeData data, Callback<Gateway.UseCodeResponse> callback) {
        Call<Gateway.UseCodeResponse> call = service.useCode(data);
        call.enqueue(callback);
    }

    public void triggerNotif(NotificationTriggerData data, Callback<String> callback) {
        Call<String> call = service.triggerNotif(data);
        call.enqueue(callback);
    }

}
