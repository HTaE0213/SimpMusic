package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toQueryList
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import com.maxrave.data.helper.MetadataLanguageHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.all
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.featured_playlists
import simpmusic.composeapp.generated.resources.playlists
import simpmusic.composeapp.generated.resources.podcasts
import simpmusic.composeapp.generated.resources.songs
import simpmusic.composeapp.generated.resources.videos

// State cho tìm kiếm
data class SearchScreenState(
    val searchType: SearchType = SearchType.ALL,
    val searchAllResult: List<SearchResultType> = emptyList(),
    val searchSongsResult: List<SongsResult> = emptyList(),
    val searchVideosResult: List<VideosResult> = emptyList(),
    val searchAlbumsResult: List<AlbumsResult> = emptyList(),
    val searchArtistsResult: List<ArtistsResult> = emptyList(),
    val searchPlaylistsResult: List<PlaylistsResult> = emptyList(),
    val searchFeaturedPlaylistsResult: List<PlaylistsResult> = emptyList(),
    val searchPodcastsResult: List<PlaylistsResult> = emptyList(),
    val suggestQueries: List<String> = emptyList(),
    val suggestYTItems: List<SearchResultType> = emptyList(),
)

// Loại tìm kiếm
enum class SearchType {
    ALL,
    SONGS,
    VIDEOS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
    FEATURED_PLAYLISTS,
    PODCASTS,
}

fun SearchType.toStringRes(): StringResource =
    when (this) {
        SearchType.ALL -> Res.string.all
        SearchType.SONGS -> Res.string.songs
        SearchType.VIDEOS -> Res.string.videos
        SearchType.ALBUMS -> Res.string.albums
        SearchType.ARTISTS -> Res.string.artists
        SearchType.PLAYLISTS -> Res.string.playlists
        SearchType.FEATURED_PLAYLISTS -> Res.string.featured_playlists
        SearchType.PODCASTS -> Res.string.podcasts
    }

// UI state cho tìm kiếm
sealed class SearchScreenUIState {
    object Empty : SearchScreenUIState()

    object Loading : SearchScreenUIState()

    object Success : SearchScreenUIState()

    object Error : SearchScreenUIState()
}

class SearchViewModel(
    private val dataStoreManager: DataStoreManager,
    private val searchRepository: SearchRepository,
) : BaseViewModel() {
    private val _searchScreenUIState = MutableStateFlow<SearchScreenUIState>(SearchScreenUIState.Empty)
    val searchScreenUIState: StateFlow<SearchScreenUIState> get() = _searchScreenUIState.asStateFlow()

    private val _searchScreenState = MutableStateFlow(SearchScreenState())
    val searchScreenState: StateFlow<SearchScreenState> get() = _searchScreenState.asStateFlow()

    private val _searchHistory: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val searchHistory: StateFlow<List<String>> get() = _searchHistory.asStateFlow()

    var regionCode: String? = null
    var language: String? = null

    init {
        regionCode = runBlocking { dataStoreManager.location.first() }
        language = runBlocking { dataStoreManager.getString(SELECTED_LANGUAGE).first() }
        getSearchHistory()
    }

    private fun getSearchHistory() {
        viewModelScope.launch {
            searchRepository.getSearchHistory().collect { values ->
                if (values.isNotEmpty()) {
                    values.toQueryList().reversed().let { list ->
                        _searchHistory.value = list
                        log("Search history updated: $list")
                    }
                } else {
                    _searchHistory.value = emptyList()
                    log("Search history is empty")
                }
            }
        }
    }

    fun insertSearchHistory(query: String) {
        viewModelScope.launch {
            if (dataStoreManager.incognitoModeEnabled.first() == DataStoreManager.TRUE) {
                return@launch
            }
            searchRepository.insertSearchHistory(SearchHistory(query = query)).collectLatest {
                Logger.d(tag, "Inserted search history: $query, $it")
                getSearchHistory()
            }
        }
    }

    fun deleteSearchHistory() {
        viewModelScope.launch {
            searchRepository.deleteSearchHistory()
            delay(1000)
            getSearchHistory()
        }
    }

    fun searchSongs(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            val flowJa = searchRepository.getSearchDataSong(query)
            val flowEn = searchRepository.getSearchDataSong(query, hl = "en", gl = "US")
            flowJa.combine(flowEn) { jaRes, enRes ->
                if (jaRes is Resource.Success && enRes is Resource.Success) {
                    val jaList = jaRes.data ?: arrayListOf()
                    val enList = enRes.data ?: arrayListOf()
                    Resource.Success(ArrayList(mergeSongs(jaList, enList)))
                } else {
                    // jaRes is Resource.Success / Error / Loading いずれもそのまま伝播
                    jaRes
                }
            }.collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { songsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.SONGS,
                                    searchSongsResult = songsList
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                    else -> {}
                }
            }
        }
    }

    fun searchAll(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            var song = ArrayList<SongsResult>()
            val video = ArrayList<VideosResult>()
            var album = ArrayList<AlbumsResult>()
            var artist = ArrayList<ArtistsResult>()
            var playlist = ArrayList<PlaylistsResult>()
            var featuredPlaylist = ArrayList<PlaylistsResult>()
            var podcast = ArrayList<PlaylistsResult>()
            val temp: ArrayList<SearchResultType> = ArrayList()

            val job1 =
                launch {
                    val flowJa = searchRepository.getSearchDataSong(query)
                    val flowEn = searchRepository.getSearchDataSong(query, hl = "en", gl = "US")
                    flowJa.combine(flowEn) { jaRes, enRes ->
                        if (jaRes is Resource.Success && enRes is Resource.Success) {
                            ArrayList(mergeSongs(jaRes.data ?: arrayListOf(), enRes.data ?: arrayListOf()))
                        } else if (jaRes is Resource.Success) {
                            jaRes.data ?: arrayListOf()
                        } else arrayListOf()
                    }.collect { song = it }
                }
            val job2 =
                launch {
                    val flowJa = searchRepository.getSearchDataArtist(query)
                    val flowEn = searchRepository.getSearchDataArtist(query, hl = "en", gl = "US")
                    flowJa.combine(flowEn) { jaRes, enRes ->
                        if (jaRes is Resource.Success && enRes is Resource.Success) {
                            ArrayList(mergeArtistResults(jaRes.data ?: arrayListOf(), enRes.data ?: arrayListOf()))
                        } else if (jaRes is Resource.Success) {
                            jaRes.data ?: arrayListOf()
                        } else arrayListOf()
                    }.collect { artist = it }
                }
            val job3 =
                launch {
                    val flowJa = searchRepository.getSearchDataAlbum(query)
                    val flowEn = searchRepository.getSearchDataAlbum(query, hl = "en", gl = "US")
                    flowJa.combine(flowEn) { jaRes, enRes ->
                        if (jaRes is Resource.Success && enRes is Resource.Success) {
                            ArrayList(mergeAlbums(jaRes.data ?: arrayListOf(), enRes.data ?: arrayListOf()))
                        } else if (jaRes is Resource.Success) {
                            jaRes.data ?: arrayListOf()
                        } else arrayListOf()
                    }.collect { album = it }
                }
            val job4 =
                launch {
                    searchRepository.getSearchDataPlaylist(query).collect { values ->
                        when (values) {
                            is Resource.Success -> values.data?.let { playlist = it }
                            is Resource.Error -> {}
                        }
                    }
                }
            val job5 =
                launch {
                    val flowJa = searchRepository.getSearchDataVideo(query)
                    val flowEn = searchRepository.getSearchDataVideo(query, hl = "en", gl = "US")
                    flowJa.combine(flowEn) { jaRes, enRes ->
                        if (jaRes is Resource.Success && enRes is Resource.Success) {
                            ArrayList(mergeVideos(jaRes.data ?: arrayListOf(), enRes.data ?: arrayListOf()))
                        } else if (jaRes is Resource.Success) {
                            jaRes.data ?: arrayListOf()
                        } else arrayListOf()
                    }.collect { video.addAll(it) }
                }
            val job6 =
                launch {
                    searchRepository.getSearchDataFeaturedPlaylist(query).collect { values ->
                        when (values) {
                            is Resource.Success -> values.data?.let { featuredPlaylist = it }
                            is Resource.Error -> {}
                        }
                    }
                }
            val job7 =
                launch {
                    searchRepository.getSearchDataPodcast(query).collect { values ->
                        when (values) {
                            is Resource.Success -> values.data?.let { podcast = it }
                            is Resource.Error -> {}
                        }
                    }
                }
            job1.join()
            job2.join()
            job3.join()
            job4.join()
            job5.join()
            job6.join()
            job7.join()

            try {
                if (artist.size >= 3) {
                    for (i in 0..2) {
                        temp += artist[i]
                    }
                    temp.addAll(song)
                    temp.addAll(video)
                    temp.addAll(album)
                    temp.addAll(playlist)
                    temp.addAll(featuredPlaylist)
                    temp.addAll(podcast)
                } else {
                    temp.addAll(artist)
                    temp.addAll(song)
                    temp.addAll(video)
                    temp.addAll(album)
                    temp.addAll(playlist)
                    temp.addAll(featuredPlaylist)
                    temp.addAll(podcast)
                }

                _searchScreenState.update { state ->
                    state.copy(
                        searchType = SearchType.ALL,
                        searchAllResult = temp,
                        searchSongsResult = song,
                        searchArtistsResult = artist,
                        searchAlbumsResult = album,
                        searchPlaylistsResult = playlist,
                        searchVideosResult = video,
                        searchFeaturedPlaylistsResult = featuredPlaylist,
                        searchPodcastsResult = podcast,
                    )
                }
                _searchScreenUIState.value = SearchScreenUIState.Success
            } catch (e: Exception) {
                e.printStackTrace()
                _searchScreenUIState.value = SearchScreenUIState.Error
            }
        }
    }

    fun suggestQuery(query: String) {
        viewModelScope.launch {
            searchRepository.getSuggestQuery(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { suggestData ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    suggestQueries = suggestData.queries,
                                    suggestYTItems = suggestData.recommendedItems,
                                )
                            }
                        }
                    }

                    is Resource.Error -> {
                        // Không cần xử lý lỗi đặc biệt cho gợi ý
                        log("Error fetching suggest queries: ${values.message}", LogLevel.ERROR)
                    }
                }
            }
        }
    }

    fun searchAlbums(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            val flowJa = searchRepository.getSearchDataAlbum(query)
            val flowEn = searchRepository.getSearchDataAlbum(query, hl = "en", gl = "US")
            flowJa.combine(flowEn) { jaRes, enRes ->
                if (jaRes is Resource.Success && enRes is Resource.Success) {
                    val jaList = jaRes.data ?: arrayListOf()
                    val enList = enRes.data ?: arrayListOf()
                    Resource.Success(ArrayList(mergeAlbums(jaList, enList)))
                } else {
                    // jaRes is Resource.Success / Error / Loading いずれもそのまま伝播
                    jaRes
                }
            }.collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { albumsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.ALBUMS,
                                    searchAlbumsResult = albumsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                    else -> {}
                }
            }
        }
    }

    fun searchFeaturedPlaylist(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataFeaturedPlaylist(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { featuredPlaylistList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.FEATURED_PLAYLISTS,
                                    searchFeaturedPlaylistsResult = featuredPlaylistList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchPodcast(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataPodcast(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { podcastList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.PODCASTS,
                                    searchPodcastsResult = podcastList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchArtists(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            val flowJa = searchRepository.getSearchDataArtist(query)
            val flowEn = searchRepository.getSearchDataArtist(query, hl = "en", gl = "US")
            flowJa.combine(flowEn) { jaRes, enRes ->
                if (jaRes is Resource.Success && enRes is Resource.Success) {
                    val jaList = jaRes.data ?: arrayListOf()
                    val enList = enRes.data ?: arrayListOf()
                    Resource.Success(ArrayList(mergeArtistResults(jaList, enList)))
                } else {
                    // jaRes is Resource.Success / Error / Loading いずれもそのまま伝播
                    jaRes
                }
            }.collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { artistsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.ARTISTS,
                                    searchArtistsResult = artistsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                    else -> {}
                }
            }
        }
    }

    fun searchPlaylists(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataPlaylist(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { playlistsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.PLAYLISTS,
                                    searchPlaylistsResult = playlistsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchVideos(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            val flowJa = searchRepository.getSearchDataVideo(query)
            val flowEn = searchRepository.getSearchDataVideo(query, hl = "en", gl = "US")
            flowJa.combine(flowEn) { jaRes, enRes ->
                if (jaRes is Resource.Success && enRes is Resource.Success) {
                    val jaList = jaRes.data ?: arrayListOf()
                    val enList = enRes.data ?: arrayListOf()
                    Resource.Success(ArrayList(mergeVideos(jaList, enList)))
                } else {
                    // jaRes is Resource.Success / Error / Loading いずれもそのまま伝播
                    jaRes
                }
            }.collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { videosList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.VIDEOS,
                                    searchVideosResult = videosList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                    else -> {}
                }
            }
        }
    }

    fun setSearchType(searchType: SearchType) {
        _searchScreenState.update { state ->
            state.copy(searchType = searchType)
        }
    }

    private fun mergeSongs(jaList: List<SongsResult>, enList: List<SongsResult>): List<SongsResult> {
        val enMap = enList.associateBy { it.videoId }
        return jaList.map { jaSong ->
            val isTitleKatakana = MetadataLanguageHelper.isKatakanaTranslation(jaSong.title ?: "")
            val artistsList = jaSong.artists ?: emptyList()
            val isArtistKatakana = artistsList.any { MetadataLanguageHelper.isKatakanaTranslation(it.name) }

            if (isTitleKatakana && isArtistKatakana) {
                val enSong = enMap[jaSong.videoId]
                if (enSong != null) {
                    jaSong.copy(
                        title = if (isTitleKatakana) enSong.title else jaSong.title,
                        artists = mergeTrackArtists(artistsList, enSong.artists),
                    )
                } else jaSong
            } else jaSong
        }
    }

    private fun mergeVideos(jaList: List<VideosResult>, enList: List<VideosResult>): List<VideosResult> {
        val enMap = enList.associateBy { it.videoId }
        return jaList.map { jaVideo ->
            val isTitleKatakana = MetadataLanguageHelper.isKatakanaTranslation(jaVideo.title ?: "")
            val artistsList = jaVideo.artists ?: emptyList()
            val isArtistKatakana = artistsList.any { MetadataLanguageHelper.isKatakanaTranslation(it.name) }

            if (isTitleKatakana && isArtistKatakana) {
                val enVideo = enMap[jaVideo.videoId]
                if (enVideo != null) {
                    jaVideo.copy(
                        title = if (isTitleKatakana) enVideo.title ?: "" else jaVideo.title,
                        artists = mergeTrackArtists(artistsList, enVideo.artists),
                    )
                } else jaVideo
            } else jaVideo
        }
    }

    private fun mergeAlbums(jaList: List<AlbumsResult>, enList: List<AlbumsResult>): List<AlbumsResult> {
        val enMap = enList.associateBy { it.browseId }
        return jaList.map { jaAlbum ->
            val isTitleKatakana = MetadataLanguageHelper.isKatakanaTranslation(jaAlbum.title ?: "")
            val artistsList = jaAlbum.artists
            val isArtistKatakana = artistsList.any { MetadataLanguageHelper.isKatakanaTranslation(it.name) }

            if (isTitleKatakana && isArtistKatakana) {
                val enAlbum = enMap[jaAlbum.browseId]
                if (enAlbum != null) {
                    jaAlbum.copy(
                        title = if (isTitleKatakana) enAlbum.title else jaAlbum.title,
                        artists = mergeTrackArtists(artistsList, enAlbum.artists),
                    )
                } else jaAlbum
            } else jaAlbum
        }
    }

    /**
     * カタカナ翻訳されたアーティスト名を、英語リストの同名アーティスト（id または name で突き合わせ）で上書きする。
     * 従来のインデックスベースは日英で件数/並び順が異なると誤対応するため、id/name ベースに変更。
     */
    private fun mergeTrackArtists(
        jaArtists: List<Artist>,
        enArtists: List<Artist>?,
    ): List<Artist> {
        if (enArtists.isNullOrEmpty()) return jaArtists
        // 优先按 id 匹配，其次按日英で同名でない限り名前が一致しないことが多いため name もフォールバックに使用
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

    private fun mergeArtistResults(jaList: List<ArtistsResult>, enList: List<ArtistsResult>): List<ArtistsResult> {
        val enMap = enList.associateBy { it.browseId }
        return jaList.map { jaArtist ->
            val isArtistKatakana = MetadataLanguageHelper.isKatakanaTranslation(jaArtist.artist ?: "")

            if (isArtistKatakana) {
                val enArtist = enMap[jaArtist.browseId]
                if (enArtist != null) {
                    jaArtist.copy(
                        artist = enArtist.artist
                    )
                } else jaArtist
            } else jaArtist
        }
    }
}
