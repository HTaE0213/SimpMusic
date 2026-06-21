@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.maxrave.simpmusic.viewModel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.DownloadState.STATE_DOWNLOADED
import com.maxrave.domain.data.entities.DownloadState.STATE_DOWNLOADING
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.browse.playlist.PlaylistState
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.extension.now
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.collectLatestResource
import com.maxrave.domain.utils.toListVideoId
import com.maxrave.domain.utils.toPlaylistEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.PlaylistUIState.Error
import com.maxrave.simpmusic.viewModel.PlaylistUIState.Loading
import com.maxrave.simpmusic.viewModel.PlaylistUIState.Success
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import com.maxrave.data.helper.MetadataLanguageHelper
import org.koin.core.component.inject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.auto_created_by_youtube_music
import simpmusic.composeapp.generated.resources.downloading
import simpmusic.composeapp.generated.resources.error
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.playlist_is_empty
import simpmusic.composeapp.generated.resources.radio
import simpmusic.composeapp.generated.resources.radio_not_available
import simpmusic.composeapp.generated.resources.removed_from_YouTube_playlist
import simpmusic.composeapp.generated.resources.shuffle
import simpmusic.composeapp.generated.resources.shuffle_not_available
import simpmusic.composeapp.generated.resources.synced
import simpmusic.composeapp.generated.resources.syncing
import simpmusic.composeapp.generated.resources.updated
import simpmusic.composeapp.generated.resources.view_count

class PlaylistViewModel(
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
) : BaseViewModel() {
    val downloadUtils: DownloadHandler by inject<DownloadHandler>()
    private var _uiState = MutableStateFlow<PlaylistUIState>(Loading)
    val uiState: StateFlow<PlaylistUIState> = _uiState

    private var _listColors = MutableStateFlow<List<Color>>(emptyList())
    val listColors: StateFlow<List<Color>> = _listColors

    private var _continuation = MutableStateFlow<String?>(null)
    val continuation: StateFlow<String?> = _continuation

    private var _playlistEntity: MutableStateFlow<PlaylistEntity?> = MutableStateFlow(null)
    var playlistEntity: StateFlow<PlaylistEntity?> = _playlistEntity

    val downloadState = _playlistEntity.map { it?.downloadState ?: 0 }.stateIn(viewModelScope, WhileSubscribed(1000), 0)
    val liked = _playlistEntity.map { it?.liked == true }.stateIn(viewModelScope, WhileSubscribed(1000), false)

    private var _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    private val _playlistEditing = MutableStateFlow(false)
    val playlistEditing: StateFlow<Boolean> = _playlistEditing

    private var _tracksListState = MutableStateFlow<ListState>(ListState.IDLE)
    val tracksListState: StateFlow<ListState> = _tracksListState

    private var collectDownloadedJob: Job? = null
    private var _downloadedList = MutableStateFlow<List<String>>(emptyList())
    val downloadedList: StateFlow<List<String>> = _downloadedList

    private var playlistEntityJob: Job? = null
    private var newUpdateJob: Job? = null
    private var checkDownloadedPlaylist: Job? = null

    /**
     * YouTubeプレイリストの並べ替えを直列実行するためのFIFOキュー。
     *
     * 設計の要点（不具合2の修正）:
     * - UIのローカル順序は指を離した時点で即時反映する。ハンドルの無効化には `_playlistEditing` を使わない。
     * - APIは単一のChannel consumerで1件ずつ直列実行し、同期的なtrySendで操作順を保証する。
     * - 失敗後は後続のindex操作を送らない。バッチ終了時にサーバー順序を再取得し、
     *   古いローカルスナップショットで後続操作まで巻き戻すことを避ける。
     * - 成功Toastは操作ごとに連発せず、キューが空になって初めて最大1回だけ表示する。
     * - ViewModel破棄時は [moveQueueJob] をキャンセルし、途中のAPI呼び出しも中断する。
     */
    private data class MoveOperation(
        val playlistId: String,
        val fromIndex: Int,
        val toIndex: Int,
    )

    private val moveQueue = Channel<MoveOperation>(capacity = Channel.UNLIMITED)
    private var moveQueueJob: Job? = null
    private var pendingMoveCount = 0
    private var moveBatchFailed = false

    init {
        viewModelScope.launch {
            val listTrackStringJob =
                launch(Dispatchers.IO) {
                    downloadState
                        .collectLatest { state ->
                            newUpdateJob?.cancel()
                            val id = playlistEntity.value?.id ?: return@collectLatest
                            if (state == STATE_DOWNLOADING || state == STATE_DOWNLOADED) {
                                getFullTracks { tracks ->
                                    newUpdateJob =
                                        launch {
                                            val listSongs = songRepository.getSongsByListVideoId(tracks.toListVideoId()).firstOrNull() ?: emptyList()
                                            if (state == STATE_DOWNLOADED && listSongs.isNotEmpty()) {
                                                listSongs.filter { it.downloadState != STATE_DOWNLOADED }.let { notDownloaded ->
                                                    if (notDownloaded.isNotEmpty()) {
                                                        downloadTracks(notDownloaded.map { it.videoId })
                                                        updatePlaylistDownloadState(id, STATE_DOWNLOADING)
                                                    } else {
                                                        updatePlaylistDownloadState(id, STATE_DOWNLOADED)
                                                    }
                                                }
                                            }
                                            downloadUtils.downloads.collectLatest { downloads ->
                                                var count = 0
                                                tracks.forEachIndexed { index, track ->
                                                    val trackDownloadState = downloads[track.videoId]?.first?.state
                                                    val videoDownloadState =
                                                        downloads[track.videoId]?.second?.state ?: DownloadHandler.State.STATE_COMPLETED
                                                    if (trackDownloadState == DownloadHandler.State.STATE_DOWNLOADING ||
                                                        videoDownloadState == DownloadHandler.State.STATE_DOWNLOADING
                                                    ) {
                                                        updatePlaylistDownloadState(id, STATE_DOWNLOADING)
                                                    } else if (trackDownloadState == DownloadHandler.State.STATE_COMPLETED &&
                                                        videoDownloadState == DownloadHandler.State.STATE_COMPLETED
                                                    ) {
                                                        count++
                                                    }
                                                    if (count == tracks.size) {
                                                        updatePlaylistDownloadState(id, STATE_DOWNLOADED)
                                                    }
                                                }
                                            }
                                        }
                                }
                            }
                        }
                }
            listTrackStringJob.join()
        }
    }

    private fun updatePlaylistDownloadState(
        id: String,
        state: Int,
    ) {
        viewModelScope.launch {
            playlistRepository.updatePlaylistDownloadState(id, state)
            delay(500)
            _playlistEntity.update {
                it?.copy(downloadState = state)
            }
        }
    }

    private fun downloadTracks(listJob: List<String>) {
        viewModelScope.launch {
            listJob.forEach { videoId ->
                songRepository.getSongById(videoId).singleOrNull()?.let { song ->
                    if (song.downloadState != STATE_DOWNLOADED) {
                        downloadUtils.downloadTrack(videoId, song.title, song.thumbnails ?: "")
                    }
                }
            }
        }
    }

    private fun resetData() {
        _uiState.value = Loading
        _playlistEntity.value = null
        _downloadedList.value = emptyList()
        _listColors.value = emptyList()
        checkDownloadedPlaylist?.cancel()
        checkDownloadedPlaylist = null
    }

    fun getData(id: String) {
        resetData()
        viewModelScope.launch {
            // Check radio
            if (id.matches(Regex("(RDAMVM|RDEM|RDAT).*"))) {
                playlistRepository
                    .getRadio(
                        id,
                        radioString = getString(Res.string.radio),
                        defaultDescription = getString(Res.string.auto_created_by_youtube_music),
                        viewString = getString(Res.string.view_count),
                    ).collect { res ->
                        val data = res.data
                        when (res) {
                            is Resource.Success if (data != null) -> {
                                Logger.d(tag, "Radio data: $data")
                                _uiState.value =
                                    Success(
                                        data =
                                            PlaylistState(
                                                id = data.first.id,
                                                title = data.first.title,
                                                isRadio = true,
                                                author = data.first.author,
                                                thumbnail =
                                                    data.first.thumbnails
                                                        .lastOrNull()
                                                        ?.url,
                                                description = data.first.description,
                                                trackCount = data.first.trackCount,
                                                year = data.first.year,
                                            ),
                                    )
                                _tracks.value = data.first.tracks
                                _continuation.value = data.second
                                if (data.second.isNullOrEmpty()) _tracksListState.value = ListState.PAGINATION_EXHAUST
                                playlistRepository.insertRadioPlaylist(data.first.toPlaylistEntity())
                            }

                            else -> {
                                _uiState.value = Error(res.message ?: "Empty response")
                            }
                        }
                    }
            } else {
                // This is an online playlist
                val flowJa = playlistRepository.getPlaylistData(id, getString(Res.string.view_count))
                val flowEn = playlistRepository.getPlaylistData(id, getString(Res.string.view_count), hl = "en", gl = "US")
                flowJa.combine(flowEn) { jaRes, enRes ->
                    if (jaRes is Resource.Success && enRes is Resource.Success) {
                        val jaData = jaRes.data
                        val enData = enRes.data
                        if (jaData != null && enData != null) {
                            val mergedTracks = mergeTracks(jaData.first.tracks, enData.first.tracks)
                            val shouldResolveHeader = MetadataLanguageHelper.shouldResolveSongMetadata(
                                jaData.first.title,
                                jaData.first.author.name,
                            )
                            val mergedPlaylistBrowse = jaData.first.copy(
                                title = if (shouldResolveHeader) {
                                    enData.first.title
                                } else {
                                    jaData.first.title
                                },
                                author = jaData.first.author.copy(
                                    name = if (shouldResolveHeader) enData.first.author.name else jaData.first.author.name,
                                ),
                                tracks = mergedTracks
                            )
                            Resource.Success(Pair(mergedPlaylistBrowse, jaData.second))
                        } else {
                            jaRes
                        }
                    } else {
                        // jaRes is Resource.Success / Error / Loading いずれもそのまま伝播
                        jaRes
                    }
                }.collect { res ->
                    val data = res.data
                    when (res) {
                        is Resource.Success if (data != null) -> {
                            Logger.d(tag, "Playlist data: $data")
                            log("Playlist endpoint: ${data.first.shuffleEndpoint}")
                            _uiState.value =
                                Success(
                                    data =
                                        PlaylistState(
                                            id = data.first.id,
                                            title = data.first.title,
                                            isRadio = false,
                                            author = data.first.author,
                                            thumbnail =
                                                data.first.thumbnails
                                                    .lastOrNull()
                                                    ?.url,
                                            description = data.first.description,
                                            trackCount = data.first.trackCount,
                                            year = data.first.year,
                                            isEditable = data.first.isEditable,
                                            shuffleEndpoint = data.first.shuffleEndpoint,
                                            radioEndpoint = data.first.radioEndpoint,
                                        ),
                                )
                            _tracks.value = data.first.tracks
                            _continuation.value = data.second
                            if (data.second.isNullOrEmpty()) _tracksListState.value = ListState.PAGINATION_EXHAUST
                            getPlaylistEntity(id = data.first.id, playlistBrowse = data.first)
                        }

                        else -> {
                            getPlaylistEntity(id)
                        }
                    }
                }
            }
        }
    }

    fun getContinuationTrack(
        playlistId: String,
        continuation: String?,
    ) {
        viewModelScope.launch {
            if (continuation.isNullOrEmpty()) {
                _tracksListState.value = ListState.PAGINATION_EXHAUST
                return@launch
            } else {
                _tracksListState.value = ListState.PAGINATING
                songRepository
                    .getContinueTrack(
                        playlistId,
                        continuation,
                        fromPlaylist = true,
                    ).collectLatest { res ->
                        res.first?.forEach { track ->
                            songRepository
                                .insertSong(
                                    track
                                        .toSongEntity()
                                        .copy(
                                            inLibrary = Config.REMOVED_SONG_DATE_TIME,
                                        ),
                                ).singleOrNull()
                                ?.let {
                                    log("Insert song: $it")
                                }
                        }
                        _tracks.update {
                            val newList = it.toMutableList()
                            newList.addAll(res.first ?: emptyList())
                            newList
                        }
                        if (res.second.isNullOrEmpty()) {
                            _continuation.value = null
                            _tracksListState.value = ListState.PAGINATION_EXHAUST
                        } else {
                            _continuation.value = res.second
                            _tracksListState.value = ListState.IDLE
                        }
                    }
            }
        }
    }

    private fun getPlaylistEntity(
        id: String,
        playlistBrowse: PlaylistBrowse? = null,
    ) {
        playlistEntityJob?.cancel()
        playlistEntityJob =
            viewModelScope.launch {
                val playlistEntity = playlistRepository.getPlaylist(id).firstOrNull()
                if (playlistBrowse != null) {
                    if (playlistEntity == null) {
                        playlistRepository.insertAndReplacePlaylist(
                            playlistBrowse.toPlaylistEntity(),
                        )
                        delay(500)
                        playlistRepository.getPlaylist(id).collectLatest { playlist ->
                            _playlistEntity.value = playlist
                            playlistRepository.updatePlaylistInLibrary(
                                playlistId = id,
                                inLibrary = now(),
                            )
                        }
                    } else {
                        _playlistEntity.value = playlistEntity
                        playlistRepository.updatePlaylistInLibrary(
                            playlistId = id,
                            inLibrary = now(),
                        )
                    }
                    playlistBrowse.tracks.forEach { tracks ->
                        songRepository
                            .insertSong(
                                tracks.toSongEntity().copy(
                                    inLibrary = Config.REMOVED_SONG_DATE_TIME,
                                ),
                            ).firstOrNull()
                            ?.let {
                                log("Insert song: $it")
                            }
                    }
                } else if (playlistEntity != null) {
                    _playlistEntity.value = playlistEntity
                    playlistRepository.updatePlaylistInLibrary(
                        playlistId = id,
                        inLibrary = now(),
                    )
                    _uiState.value =
                        Success(
                            data =
                                PlaylistState(
                                    id = playlistEntity.id,
                                    title = playlistEntity.title,
                                    isRadio = false,
                                    author =
                                        Author(
                                            id = "",
                                            name = playlistEntity.author ?: "",
                                        ),
                                    thumbnail = playlistEntity.thumbnails,
                                    description = playlistEntity.description,
                                    trackCount = playlistEntity.trackCount,
                                    year = playlistEntity.year ?: now().year.toString(),
                                ),
                        )
                    _tracksListState.value = ListState.LOADING
                    playlistEntity.tracks?.let {
                        songRepository
                            .getSongsByListVideoId(it)
                            .singleOrNull()
                            ?.let { song ->
                                _tracks.value = song.map { it.toTrack() }
                            }
                    }
                    _tracksListState.value = ListState.PAGINATION_EXHAUST
                    if (playlistEntity.downloadState != STATE_DOWNLOADED) {
                        checkDownloadedPlaylist =
                            launch {
                                val listSong =
                                    songRepository
                                        .getSongsByListVideoId(
                                            playlistEntity.tracks ?: emptyList(),
                                        ).firstOrNull()
                                Logger.d(tag, "List song: $listSong")
                                if (!listSong.isNullOrEmpty() && listSong.size == playlistEntity.tracks?.size &&
                                    listSong.all {
                                        it.downloadState == STATE_DOWNLOADED
                                    }
                                ) {
                                    updatePlaylistDownloadState(playlistEntity.id, STATE_DOWNLOADED)
                                }
                            }
                    }
                } else {
                    _uiState.value = Error("Empty response")
                }
            }
    }

    fun setBrush(listColors: List<Color>) {
        _listColors.value = listColors
    }

    fun updatePlaylistLiked(
        liked: Boolean,
        id: String,
    ) {
        viewModelScope.launch {
            val tempLiked = if (liked) 1 else 0
            playlistRepository.updatePlaylistLiked(id, tempLiked)
            _playlistEntity.update {
                it?.copy(
                    liked = tempLiked == 1,
                )
            }
            getFullTracks { }
        }
    }

    fun onUIEvent(event: PlaylistUIEvent) {
        val data = uiState.value.data ?: return
        when (event) {
            is PlaylistUIEvent.ItemClick -> {
                val videoId = event.videoId
                val loadedList = tracks.value
                val clickedSong = loadedList.first { it.videoId == videoId }
                val index = loadedList.indexOf(clickedSong)
                setQueueData(
                    QueueData.Data(
                        listTracks = loadedList.toCollection(arrayListOf<Track>()),
                        firstPlayedTrack = clickedSong,
                        playlistId = data.id,
                        playlistName = "${
                            getString(
                                Res.string.playlist,
                            )
                        } \"${data.title}\"",
                        playlistType = PlaylistType.PLAYLIST,
                        continuation = continuation.value,
                    ),
                )
                loadMediaItem(
                    clickedSong,
                    Config.PLAYLIST_CLICK,
                    index,
                )
            }

            PlaylistUIEvent.PlayAll -> {
                val loadedList = tracks.value
                if (loadedList.isEmpty()) {
                    makeToast(
                        getString(Res.string.playlist_is_empty),
                    )
                    return
                }
                val clickedSong = loadedList.first()
                setQueueData(
                    QueueData.Data(
                        listTracks = loadedList.toCollection(arrayListOf<Track>()),
                        firstPlayedTrack = clickedSong,
                        playlistId = data.id,
                        playlistName = "${
                            getString(
                                Res.string.playlist,
                            )
                        } \"${data.title}\"",
                        playlistType = PlaylistType.PLAYLIST,
                        continuation = continuation.value,
                    ),
                )
                loadMediaItem(
                    clickedSong,
                    Config.PLAYLIST_CLICK,
                    0,
                )
            }

            PlaylistUIEvent.Shuffle -> {
                val shuffleEndpoint = data.shuffleEndpoint
                if (shuffleEndpoint == null) {
                    makeToast(
                        getString(Res.string.shuffle_not_available),
                    )
                    return
                } else {
                    viewModelScope.launch {
                        songRepository.getRadioFromEndpoint(shuffleEndpoint).collectLatest { res ->
                            val result = res.data
                            when (res) {
                                is Resource.Success if (result != null) -> {
                                    Logger.d(tag, "Shuffle data: ${result.first.size}")
                                    setQueueData(
                                        QueueData.Data(
                                            listTracks = result.first.toCollection(arrayListOf<Track>()),
                                            firstPlayedTrack = result.first.firstOrNull() ?: return@collectLatest,
                                            playlistId = shuffleEndpoint.playlistId,
                                            playlistName = "\"${data.title}\" ${getString(Res.string.shuffle)}",
                                            playlistType = PlaylistType.RADIO,
                                            continuation = result.second,
                                        ),
                                    )
                                    loadMediaItem(
                                        result.first.firstOrNull() ?: return@collectLatest,
                                        Config.RADIO_CLICK,
                                        0,
                                    )
                                }

                                else -> {
                                    makeToast(
                                        res.message ?: getString(Res.string.error),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PlaylistUIEvent.StartRadio -> {
                val radioEndpoint = data.radioEndpoint
                if (radioEndpoint == null) {
                    makeToast(
                        getString(Res.string.radio_not_available),
                    )
                    return
                } else {
                    viewModelScope.launch {
                        songRepository.getRadioFromEndpoint(radioEndpoint).collectLatest { res ->
                            val result = res.data
                            when (res) {
                                is Resource.Success if (result != null) -> {
                                    Logger.d(tag, "Radio data: ${result.first.size}")
                                    setQueueData(
                                        QueueData.Data(
                                            listTracks = result.first.toCollection(arrayListOf<Track>()),
                                            firstPlayedTrack = result.first.firstOrNull() ?: return@collectLatest,
                                            playlistId = radioEndpoint.playlistId,
                                            playlistName = "\"${data.title}\" ${getString(Res.string.radio)}",
                                            playlistType = PlaylistType.RADIO,
                                            continuation = result.second,
                                        ),
                                    )
                                    loadMediaItem(
                                        result.first.firstOrNull() ?: return@collectLatest,
                                        Config.RADIO_CLICK,
                                        0,
                                    )
                                }

                                else -> {
                                    makeToast(
                                        res.message ?: getString(Res.string.error),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PlaylistUIEvent.Download -> {
                downloadFullPlaylist()
            }

            PlaylistUIEvent.Favorite -> {
                updatePlaylistLiked(!liked.value, data.id)
            }
        }
    }

    fun getFullTracks(callback: (List<Track>) -> Unit) {
        viewModelScope.launch {
            if (tracksListState.value == ListState.PAGINATION_EXHAUST) {
                _playlistEntity.value
                    ?.copy(
                        tracks = tracks.value.toListVideoId(),
                        trackCount = tracks.value.size,
                    )?.let {
                        playlistRepository.insertAndReplacePlaylist(it)
                    }
                callback(tracks.value)
            } else {
                val id = uiState.value.data?.id ?: return@launch
                tracksListState.collectLatest { state ->
                    if (state == ListState.PAGINATION_EXHAUST) {
                        _playlistEntity.value
                            ?.copy(
                                tracks = tracks.value.toListVideoId(),
                                trackCount = tracks.value.size,
                            )?.let {
                                playlistRepository.insertAndReplacePlaylist(it)
                            }
                        callback(tracks.value)
                    } else if (state != ListState.PAGINATING) {
                        getContinuationTrack(id, continuation.value)
                    }
                }
            }
        }
    }

    fun saveToLocal(tracks: List<Track>) {
        viewModelScope.launch {
            val data = uiState.value.data ?: return@launch
            localPlaylistRepository
                .syncYouTubePlaylistToLocalPlaylist(
                    data,
                    tracks,
                    getString(Res.string.synced),
                    getString(Res.string.error),
                ).collectLatestResource(
                    onSuccess = {
                        makeToast(it)
                    },
                    onLoading = {
                        makeToast(getString(Res.string.syncing))
                    },
                    onError = {
                        makeToast(it)
                    },
                )
        }
    }

    fun downloadFullPlaylist() {
        viewModelScope.launch {
            val id = playlistEntity.value?.id ?: return@launch
            makeToast(getString(Res.string.downloading))
            updatePlaylistDownloadState(id, STATE_DOWNLOADING)
            getFullTracks { tracks ->
                tracks.forEach {
                    viewModelScope.launch {
                        downloadUtils.downloadTrack(it.videoId, it.title, it.thumbnails?.lastOrNull()?.url ?: "")
                    }
                }
            }
        }
    }

    fun updatePlaylistTitle(
        title: String,
        id: String,
    ) {
        viewModelScope.launch {
            playlistRepository
                .updateYourYouTubePlaylistTitle(
                    id,
                    title,
                ).collect {
                    when (it) {
                        is Resource.Success -> {
                            getData(id)
                            makeToast(it.data ?: getString(Res.string.synced))
                        }

                        is Resource.Error -> {
                            makeToast(it.message ?: getString(Res.string.error))
                        }
                    }
                }
        }
    }

    fun removeYouTubePlaylistItem(
        playlistId: String,
        track: Track,
    ) {
        if (_playlistEditing.value) return
        _playlistEditing.value = true
        viewModelScope.launch {
            try {
                playlistRepository.removeYouTubePlaylistItem(playlistId, track.videoId).collect { result ->
                    when (result) {
                        is Resource.Success -> {
                            _tracks.update { current -> current.filterNot { it.videoId == track.videoId } }
                            _uiState.update { state ->
                                if (state is Success) {
                                    state.data?.let { data ->
                                        Success(data.copy(trackCount = _tracks.value.size))
                                    } ?: state
                                } else {
                                    state
                                }
                            }
                            makeToast(getString(Res.string.removed_from_YouTube_playlist))
                        }

                        is Resource.Error -> makeToast(result.message ?: getString(Res.string.error))
                    }
                }
            } finally {
                _playlistEditing.value = false
            }
        }
    }

    /**
     * プレイリスト項目の並べ替え。
     *
     * 指を離した時点でUIローカル順序を即時反映し、API呼び出しは [moveQueue] へ直列投入する。
     * これにより API 通信中でも次のドラッグ操作が可能になる。
     * `_playlistEditing` はドラッグハンドル無効化には使わず、後続バックグラウンドが進行中かどうかの
     * 参考状態としてのみ用いる（画面側は基本的にこれを見てハンドルを止めない）。
     */
    fun moveYouTubePlaylistItem(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex == toIndex) {
            // 元位置へ戻った操作。UIもAPIも変更しない。
            return
        }
        if (fromIndex !in _tracks.value.indices || toIndex !in _tracks.value.indices) return

        // UIローカル順序を即時反映。
        val reordered = _tracks.value.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        _tracks.value = reordered
        val operation = MoveOperation(
            playlistId = playlistId,
            fromIndex = fromIndex,
            toIndex = toIndex,
        )
        pendingMoveCount += 1
        if (moveQueue.trySend(operation).isFailure) {
            pendingMoveCount -= 1
            makeToast(getString(Res.string.error))
            return
        }
        ensureMoveQueueConsumer()
    }

    /**
     * キューの消費コルーチンを1つだけ起動する。
     */
    private fun ensureMoveQueueConsumer() {
        if (moveQueueJob?.isActive == true) return
        moveQueueJob =
            viewModelScope.launch {
                for (operation in moveQueue) {
                    processMoveOperation(operation)
                }
            }
    }

    private suspend fun processMoveOperation(operation: MoveOperation) {
        _playlistEditing.value = true
        try {
            // A failed index operation invalidates the index basis for the rest of this
            // batch. Skip those requests and reconcile once the batch has drained.
            if (!moveBatchFailed) {
                var operationFailed = false
                playlistRepository
                    .moveYouTubePlaylistItem(operation.playlistId, operation.fromIndex, operation.toIndex)
                    .collect { result ->
                        when (result) {
                            is Resource.Success -> Unit
                            is Resource.Error -> {
                                operationFailed = true
                                Logger.w(tag, "moveYouTubePlaylistItem failed: ${result.message}")
                            }
                        }
                    }
                if (operationFailed) {
                    moveBatchFailed = true
                }
            }
        } finally {
            pendingMoveCount -= 1
            if (pendingMoveCount == 0) {
                if (moveBatchFailed) {
                    refreshTracksFromServer(operation.playlistId)
                    makeToast(getString(Res.string.error))
                } else {
                    makeToast(getString(Res.string.updated))
                }
                moveBatchFailed = false
                _playlistEditing.value = false
            }
        }
    }

    /**
     * API失敗時にローカル順序とサーバー順序が乖離した場合の安全な復旧。
     * サーバーから最新のプレイリストを再取得し、_tracks を上書きする。
     * UI側での編集操作が進行中であっても、再取得結果が正とみなす。
     */
    private suspend fun refreshTracksFromServer(playlistId: String) {
        runCatching {
            playlistRepository
                .getPlaylistData(playlistId, getString(Res.string.view_count))
                .firstOrNull { it is Resource.Success }
        }.getOrNull()
            ?.let { res -> (res as? Resource.Success)?.data }
            ?.let { (browse, _) ->
                _tracks.value = browse.tracks
            }
    }

    override fun onCleared() {
        super.onCleared()
        collectDownloadedJob?.cancel()
        playlistEntityJob?.cancel()
        moveQueueJob?.cancel()
        moveQueue.close()
    }

    private fun mergeTracks(jaList: List<Track>, enList: List<Track>): List<Track> {
        val enMap = enList.associateBy { it.videoId }
        return jaList.map { jaTrack ->
            val isTitleKatakana = MetadataLanguageHelper.isKatakanaTranslation(jaTrack.title)
            val artistsList = jaTrack.artists ?: emptyList()
            val isArtistKatakana = artistsList.any { MetadataLanguageHelper.isKatakanaTranslation(it.name) }

            if (isTitleKatakana && isArtistKatakana) {
                val enTrack = enMap[jaTrack.videoId]
                if (enTrack != null) {
                    jaTrack.copy(
                        title = if (isTitleKatakana) enTrack.title else jaTrack.title,
                        artists = mergeArtists(artistsList, enTrack.artists),
                    )
                } else jaTrack
            } else jaTrack
        }
    }

    /**
     * カタカナ翻訳されたアーティスト名を、英語リストの同一アーティスト（id で突き合わせ）で上書きする。
     * 日英で並び順が異なるためインデックスではなく id で対応付ける。
     */
    private fun mergeArtists(
        jaArtists: List<Artist>,
        enArtists: List<Artist>?,
    ): List<Artist> {
        if (enArtists.isNullOrEmpty()) return jaArtists
        val enById = enArtists.filter { !it.id.isNullOrEmpty() }.associateBy { it.id }
        return jaArtists.map { jaArtist ->
            if (!MetadataLanguageHelper.isKatakanaTranslation(jaArtist.name)) {
                jaArtist
            } else {
                val resolved = jaArtist.id?.let { enById[it] }
                resolved?.let { jaArtist.copy(name = it.name) } ?: jaArtist
            }
        }
    }
}

sealed class PlaylistUIState(
    val data: PlaylistState? = null,
    val message: String? = null,
) {
    data object Loading : PlaylistUIState()

    class Success(
        data: PlaylistState,
    ) : PlaylistUIState(
            data = data,
        )

    class Error(
        message: String? = null,
    ) : PlaylistUIState(
            message = message,
        )
}

sealed class PlaylistUIEvent {
    data object PlayAll : PlaylistUIEvent()

    data object Shuffle : PlaylistUIEvent()

    data object StartRadio : PlaylistUIEvent()

    data class ItemClick(
        val videoId: String,
    ) : PlaylistUIEvent()

    data object Favorite : PlaylistUIEvent()

    data object Download : PlaylistUIEvent()
}

enum class ListState {
    IDLE,
    LOADING,
    PAGINATING,
    ERROR,
    PAGINATION_EXHAUST,
}
