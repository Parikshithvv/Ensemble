package com.example.ensemble

import android.app.Application
import com.example.ensemble.di.dataModule
import com.example.ensemble.di.presentationModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class EnsembleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@EnsembleApp)
            modules(dataModule, presentationModule)
        }
    }
}
