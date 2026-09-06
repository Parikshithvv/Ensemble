package com.example.ensemble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ensemble.presentation.EnsembleNavGraph
import com.example.ensemble.ui.theme.EnsembleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnsembleTheme {
                EnsembleNavGraph()
            }
        }
    }
}