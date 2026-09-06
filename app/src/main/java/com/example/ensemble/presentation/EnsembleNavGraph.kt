package com.example.ensemble.presentation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ensemble.presentation.processing.ProcessingRoot
import com.example.ensemble.presentation.processing.ProcessingViewModel
import com.example.ensemble.presentation.results.ResultsEvent
import com.example.ensemble.presentation.results.ResultsScreen
import com.example.ensemble.presentation.results.ResultsViewModel
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val PROCESSING = "processing"
    const val RESULTS = "results"
}

@Composable
fun EnsembleNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Share ProcessingViewModel instance across destinations
    val processingViewModel: ProcessingViewModel = koinViewModel()
    val resultsViewModel: ResultsViewModel = koinViewModel()

    NavHost(navController = navController, startDestination = Routes.PROCESSING) {
        composable(Routes.PROCESSING) {
            ProcessingRoot(
                viewModel = processingViewModel,
                onNavigateToResults = {
                    val pState = processingViewModel.state.value
                    resultsViewModel.setResults(
                        persons = pState.persons,
                        facesDetected = pState.facesDetected,
                        framesProcessed = pState.framesProcessed,
                        videoUri = pState.selectedVideoUri?.toString() ?: ""
                    )
                    navController.navigate(Routes.RESULTS)
                }
            )
        }
        composable(Routes.RESULTS) {
            val resultsState by resultsViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                resultsViewModel.events.collect { event ->
                    when (event) {
                        is ResultsEvent.ShareUri -> {
                            shareCollageUri(context, event.uri)
                        }
                        is ResultsEvent.ShowToast -> {
                            Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            ResultsScreen(
                state = resultsState,
                onAction = { action -> resultsViewModel.onAction(action, context) },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

private fun shareCollageUri(context: android.content.Context, uri: Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, "Ensemble Video Face Clustering Collage")
        putExtra(Intent.EXTRA_TEXT, "Check out the identified people and key moments from my video using Ensemble!")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Collage Image"))
}
