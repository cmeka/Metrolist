/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ArtistSortDescendingKey
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.ArtistSortTypeKey
import com.metrolist.music.constants.ArtistViewTypeKey
import com.metrolist.music.constants.CONTENT_TYPE_ARTIST
import com.metrolist.music.constants.CONTENT_TYPE_HEADER
import com.metrolist.music.constants.GridItemSize
import com.metrolist.music.constants.GridItemsSizeKey
import com.metrolist.music.constants.GridThumbnailHeight
import com.metrolist.music.constants.LibraryViewType
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.ArtistGridItem
import com.metrolist.music.ui.component.ArtistListItem
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.component.AlbumListItem
import com.metrolist.music.ui.component.AlbumGridItem
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.utils.LocalMusicRepository
import com.metrolist.music.utils.LocalSyncStatus
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LocalMusicViewModel
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalMusicScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current

    var viewType by rememberEnumPreference(ArtistViewTypeKey, LibraryViewType.GRID)
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        ArtistSortTypeKey,
        ArtistSortType.NAME
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(ArtistSortDescendingKey, false)
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)

    val hasPermission by viewModel.hasPermission.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val localArtists by viewModel.localArtists.collectAsState()

    Timber.d("LocalMusicScreen: COMPOSE - hasPermission=$hasPermission, syncStatus=$syncStatus, syncProgress=$syncProgress, artistCount=${localArtists.size}")

    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var isSearching by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val filteredArtists = remember(localArtists, searchQuery.text, sortType, sortDescending) {
        localArtists
            .filter { artist ->
                searchQuery.text.isEmpty() || artist.artist.name.contains(searchQuery.text, ignoreCase = true)
            }
            .let { artists ->
                when (sortType) {
                    ArtistSortType.NAME -> artists.sortedBy { it.artist.name.lowercase() }
                    ArtistSortType.SONG_COUNT -> artists.sortedBy { it.songCount }
                    else -> artists
                }.let { if (sortDescending) it.reversed() else it }
            }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.d("LocalMusicScreen: Permission result callback - granted=$granted")
        if (granted) {
            Timber.d("LocalMusicScreen: Permission granted, updating state and launching sync")
            viewModel.refreshPermissionState()
            coroutineScope.launch {
                viewModel.syncLocalMusic()
            }
        } else {
            Timber.w("LocalMusicScreen: Permission DENIED by user")
        }
    }

    LaunchedEffect(hasPermission) {
        Timber.d("LocalMusicScreen: LaunchedEffect(hasPermission=$hasPermission)")
        if (!hasPermission) {
            Timber.d("LocalMusicScreen: Showing permission dialog")
            showPermissionDialog = true
        } else {
            Timber.d("LocalMusicScreen: Permission already granted, no request needed")
        }
    }

    if (showPermissionDialog) {
        DefaultDialog(
            onDismiss = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.local_music_permission_title)) },
            buttons = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = {
                    showPermissionDialog = false
                    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    Timber.d("LocalMusicScreen: Launching permission request for $permission")
                    permissionLauncher.launch(permission)
                }) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        ) {
            Text(stringResource(R.string.local_music_permission_desc))
        }
    }

    val filterContent = @Composable {
        Row {
            Spacer(Modifier.width(12.dp))
            FilterChip(
                label = { Text(stringResource(R.string.filter_local)) },
                selected = true,
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = { navController.navigateUp() },
                shape = RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.close), contentDescription = "")
                },
            )
        }
    }

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            when (viewType) {
                LibraryViewType.LIST -> lazyListState.animateScrollToItem(0)
                LibraryViewType.GRID -> lazyGridState.animateScrollToItem(0)
            }
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val headerContent = @Composable {
        Column {
            if (syncStatus is LocalSyncStatus.Syncing) {
                LinearProgressIndicator(
                    progress = { syncProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.text.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = TextFieldValue("") }) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = null
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp),
            ) {
                SortHeader(
                    sortType = sortType,
                    sortDescending = sortDescending,
                    onSortTypeChange = onSortTypeChange,
                    onSortDescendingChange = onSortDescendingChange,
                    sortTypeText = { sortType ->
                        when (sortType) {
                            ArtistSortType.CREATE_DATE -> R.string.sort_by_create_date
                            ArtistSortType.NAME -> R.string.sort_by_name
                            ArtistSortType.SONG_COUNT -> R.string.sort_by_song_count
                            ArtistSortType.PLAY_TIME -> R.string.sort_by_play_time
                        }
                    },
                )

                Spacer(Modifier.weight(1f))

                Text(
                    text = pluralStringResource(
                        R.plurals.n_artist,
                        filteredArtists.size,
                        filteredArtists.size
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )

                IconButton(
                    onClick = { isSearching = !isSearching },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                    )
                }

                IconButton(
                    onClick = { viewType = viewType.toggle() },
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            when (viewType) {
                                LibraryViewType.LIST -> R.drawable.list
                                LibraryViewType.GRID -> R.drawable.grid_view
                            },
                        ),
                        contentDescription = null,
                    )
                }
            }
        }
    }

    // Show full-screen loading during initial sync (no data yet)
    val isInitialSync = hasPermission && syncStatus is LocalSyncStatus.Syncing && localArtists.isEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasPermission) {
            // Just show filter chip, dialog handles permission request
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                filterContent()
            }
        } else if (isInitialSync) {
            // Full-screen loading for initial sync
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                filterContent()
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.padding(16.dp))
                Text(
                    text = stringResource(R.string.syncing_local_music),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    text = "${(syncProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            PullToRefreshBox(
                isRefreshing = syncStatus is LocalSyncStatus.Syncing,
                onRefresh = {
                    Timber.d("LocalMusicScreen: Pull-to-refresh triggered")
                    coroutineScope.launch {
                        viewModel.syncLocalMusic()
                    }
                },
            ) {
                when (viewType) {
                    LibraryViewType.LIST ->
                        LazyColumn(
                            state = lazyListState,
                            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                        ) {
                            item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                                filterContent()
                            }

                            item(key = "header", contentType = CONTENT_TYPE_HEADER) {
                                headerContent()
                            }

                            if (filteredArtists.isEmpty() && syncStatus !is LocalSyncStatus.Syncing) {
                                item(key = "empty_placeholder") {
                                    EmptyPlaceholder(
                                        icon = R.drawable.artist,
                                        text = stringResource(R.string.no_local_artists),
                                    )
                                }
                            }

                            items(
                                items = filteredArtists,
                                key = { it.id },
                                contentType = { CONTENT_TYPE_ARTIST },
                            ) { artist ->
                                ArtistListItem(
                                    artist = artist,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                Timber.d("LocalMusicScreen: Artist clicked - id=${artist.id}, name=${artist.artist.name}")
                                                navController.navigate("local_artist/${artist.id}")
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                Timber.d("LocalMusicScreen: Artist long-pressed - id=${artist.id}")
                                            },
                                        )
                                        .animateItem()
                                )
                            }
                        }

                    LibraryViewType.GRID ->
                        LazyVerticalGrid(
                            state = lazyGridState,
                            columns = GridCells.Adaptive(
                                minSize = GridThumbnailHeight + if (gridItemSize == GridItemSize.BIG) 24.dp else (-24).dp,
                            ),
                            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                        ) {
                            item(
                                key = "filter",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = CONTENT_TYPE_HEADER,
                            ) {
                                filterContent()
                            }

                            item(
                                key = "header",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = CONTENT_TYPE_HEADER,
                            ) {
                                headerContent()
                            }

                            if (filteredArtists.isEmpty() && syncStatus !is LocalSyncStatus.Syncing) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    EmptyPlaceholder(
                                        icon = R.drawable.artist,
                                        text = stringResource(R.string.no_local_artists),
                                    )
                                }
                            }

                            items(
                                items = filteredArtists,
                                key = { it.id },
                                contentType = { CONTENT_TYPE_ARTIST },
                            ) { artist ->
                                ArtistGridItem(
                                    artist = artist,
                                    fillMaxWidth = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                Timber.d("LocalMusicScreen: Artist grid item clicked - id=${artist.id}, name=${artist.artist.name}")
                                                navController.navigate("local_artist/${artist.id}")
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                Timber.d("LocalMusicScreen: Artist long-pressed - id=${artist.id}")
                                            },
                                        )
                                        .animateItem()
                                )
                            }
                        }
                }
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.local_music)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
