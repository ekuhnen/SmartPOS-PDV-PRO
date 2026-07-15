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
            .addInterceptor(com.plugpdv.pdv.api.DeviceIdInterceptor(context))
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
}
