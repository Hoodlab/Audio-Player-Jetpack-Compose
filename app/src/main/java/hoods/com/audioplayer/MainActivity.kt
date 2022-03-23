package hoods.com.audioplayer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.AndroidEntryPoint
import hoods.com.audioplayer.ui.audio.AudioViewModel
import hoods.com.audioplayer.ui.audio.HomeScreen
import hoods.com.audioplayer.ui.theme.AudioPlayerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioPlayerTheme {
                val permissionState = rememberPermissionState(
                    permission = Manifest.permission.READ_EXTERNAL_STORAGE
                )

                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(key1 = lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            permissionState.launchPermissionRequest()
                        }

                    }
                    lifecycleOwner.lifecycle.addObserver(observer)


                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }

                }


                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    if (permissionState.hasPermission) {

                        val audioViewModel = viewModel(
                            modelClass = AudioViewModel::class.java
                        )
                        val audioList = audioViewModel.audioList
                        HomeScreen(
                            progress = audioViewModel.currentAudioProgress.value,
                            onProgressChange = {
                                audioViewModel.seekTo(it)
                            },
                            isAudioPlaying = audioViewModel.isAudioPlaying,
                            audioList = audioList,
                            currentPlayingAudio = audioViewModel
                                .currentPlayingAudio.value,
                            onStart = {
                                audioViewModel.playAudio(it)
                            },
                            onItemClick = {
                                audioViewModel.playAudio(it)
                            },
                            onNext = {
                                audioViewModel.skipToNext()
                            }
                        )


                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "Grant permission first to use this app")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AudioPlayerTheme {
        Greeting("Android")
    }
}