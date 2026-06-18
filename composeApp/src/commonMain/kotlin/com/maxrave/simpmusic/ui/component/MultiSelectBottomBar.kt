package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.simpmusic.viewModel.SharedViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.multiselect_add_to_playlist
import simpmusic.composeapp.generated.resources.multiselect_add_to_queue
import simpmusic.composeapp.generated.resources.multiselect_added_to_playlist
import simpmusic.composeapp.generated.resources.multiselect_clear_selection
import simpmusic.composeapp.generated.resources.multiselect_count_selected
import simpmusic.composeapp.generated.resources.multiselect_error
import simpmusic.composeapp.generated.resources.multiselect_select_all
import simpmusic.composeapp.generated.resources.multiselect_yt_playlist_updated
import simpmusic.composeapp.generated.resources.play_next

@Composable
fun MultiSelectBottomBar(
    sharedViewModel: SharedViewModel,
    modifier: Modifier = Modifier,
) {
    val isSelectionMode by sharedViewModel.isSelectionMode.collectAsState()
    val selectedTracks by sharedViewModel.selectedTracks.collectAsState()

    val localPlaylistRepository: LocalPlaylistRepository = koinInject()
    val localPlaylists by localPlaylistRepository.getAllLocalPlaylists().collectAsState(initial = emptyList())
    val playlistRepository: PlaylistRepository = koinInject()
    val youtubePlaylists by playlistRepository.getLibraryPlaylist().collectAsState(initial = null)

    var showPlaylistSheet by remember { mutableStateOf(false) }

    val addedToPlaylistMessage = stringResource(Res.string.multiselect_added_to_playlist)
    val ytPlaylistUpdatedMessage = stringResource(Res.string.multiselect_yt_playlist_updated)
    val errorMessage = stringResource(Res.string.multiselect_error)

    AnimatedVisibility(
        visible = isSelectionMode,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1F1F1F),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(Res.string.multiselect_count_selected, selectedTracks.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )

                    IconButton(onClick = { sharedViewModel.clearSelection() }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(Res.string.multiselect_clear_selection),
                            tint = Color.White,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(
                        onClick = { sharedViewModel.selectAllTracks() },
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = stringResource(Res.string.multiselect_select_all),
                            tint = Color.White,
                        )
                    }

                    IconButton(onClick = { sharedViewModel.playSelectedNext() }) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(Res.string.play_next),
                            tint = Color.White,
                        )
                    }

                    IconButton(onClick = { sharedViewModel.addSelectedToQueue() }) {
                        Icon(
                            imageVector = Icons.Default.Queue,
                            contentDescription = stringResource(Res.string.multiselect_add_to_queue),
                            tint = Color.White,
                        )
                    }

                    IconButton(onClick = { showPlaylistSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = stringResource(Res.string.multiselect_add_to_playlist),
                            tint = Color.White,
                        )
                    }

                }
            }
        }
    }

    if (showPlaylistSheet) {
        AddToPlaylistModalBottomSheet(
            isBottomSheetVisible = showPlaylistSheet,
            listLocalPlaylist = localPlaylists,
            listYouTubePlaylist = youtubePlaylists?.filter { it.browseId != "VLLM" } ?: emptyList(),
            initiallyShowYouTubePlaylists = true,
            onClick = { localPlaylist ->
                sharedViewModel.addSelectedToLocalPlaylist(
                    playlistId = localPlaylist.id,
                    successMessage = addedToPlaylistMessage,
                    updatedYtMessage = ytPlaylistUpdatedMessage,
                    errorMessage = errorMessage,
                )
                showPlaylistSheet = false
            },
            onYTPlaylistClick = { playlist ->
                sharedViewModel.addSelectedToYouTubePlaylist(
                    playlistId = playlist.browseId,
                    errorMessage = errorMessage,
                )
                showPlaylistSheet = false
            },
            onDismiss = { showPlaylistSheet = false },
        )
    }
}
