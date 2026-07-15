package com.plugpdv.pdv.di

import android.content.Context
import com.plugpdv.pdv.api.DeviceIdInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    private const val SUPABASE_URL = "https://ypvcxgkzolzxggfrmzlz.supabase.co"
    // TODO: Adicione sua ANON_KEY do Supabase aqui ou configure via BuildConfig
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlwdmN4Z2t6b2x6eGdnZnJtemx6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzIxMjI4NDEsImV4cCI6MjA4NzY5ODg0MX0.NsUCjtnLg4rsHNhAXItIKxvJe_nl1mX7Ssa2XxF9VhU" 

    @Provides
    @Singleton
    fun provideSupabaseClient(@ApplicationContext context: Context): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
            install(Realtime)
            install(Functions)
            install(Auth)
            
            httpEngine = io.ktor.client.engine.okhttp.OkHttp.create {
                config {
                    addInterceptor(com.plugpdv.pdv.api.DeviceIdInterceptor(context))
                }
            }
        }
    }
}
