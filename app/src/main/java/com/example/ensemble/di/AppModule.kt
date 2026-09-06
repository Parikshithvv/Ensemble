package com.example.ensemble.di

import com.example.ensemble.data.FaceEmbedder
import com.example.ensemble.data.MlKitFaceDetector
import com.example.ensemble.data.PersonClusterer
import com.example.ensemble.data.VideoFrameExtractor
import com.example.ensemble.presentation.processing.ProcessingViewModel
import com.example.ensemble.presentation.results.ResultsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val dataModule = module {
    single { VideoFrameExtractor(androidContext()) }
    single { MlKitFaceDetector() }
    single { FaceEmbedder(androidContext()) }
    single { PersonClusterer() }
}

val presentationModule = module {
    viewModel { ProcessingViewModel(androidContext() as android.app.Application, get(), get(), get(), get()) }
    viewModel { ResultsViewModel() }
}
