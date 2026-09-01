package com.plugpdv.pdv.di

import com.plugpdv.pdv.api.PosApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/"

    @Provides
    @Singleton
    fun provideOkHttpClient(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): okhttp3.OkHttpClient {
        return okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(com.plugpdv.pdv.api.AppHeadersInterceptor(context))
            .addInterceptor(com.plugpdv.pdv.api.BlockResponseInterceptor())
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: okhttp3.OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): PosApiService {
        return retrofit.create(PosApiService::class.java)
    }

    @Provides
    @Singleton
    @ComandaDispatcherClient
    fun provideComandaDispatcherOkHttpClient(baseClient: okhttp3.OkHttpClient): okhttp3.OkHttpClient {
        return baseClient.newBuilder()
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @ComandaDispatcherService
    fun provideComandaDispatcherApiService(
        @ComandaDispatcherClient client: okhttp3.OkHttpClient,
        retrofit: Retrofit
    ): PosApiService {
        return retrofit.newBuilder()
            .client(client)
            .build()
            .create(PosApiService::class.java)
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComandaDispatcherClient

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComandaDispatcherService
