package org.telecloud.photos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.scale
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlin.math.max
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.decode.VideoFrameDecoder
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import org.telecloud.photos.data.BackupStatus
import org.telecloud.photos.data.MediaItem
import org.telecloud.photos.ui.ConnectionState
import org.telecloud.photos.ui.PhotosViewModel
import org.telecloud.photos.network.NetworkClient

// Single shared decoder factory — never re-allocate per item (main scroll perf fix)
private val sharedVideoFrameDecoderFactory = VideoFrameDecoder.Factory()

class MainActivity : ComponentActivity() {

    private val viewModel: PhotosViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            viewModel.loadLocalMedia()
        } else {
            Toast.makeText(this, "Permissions are required to show photos", Toast.LENGTH_LONG).show()
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadLocalMedia()
        }
    }

    private fun requestDeleteMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { Uri.parse(it.contentUri) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } else {
                items.forEach { item ->
                    contentResolver.delete(Uri.parse(item.contentUri), null, null)
                }
                viewModel.loadLocalMedia()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể xóa ảnh: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TeleCloudTheme {
                AppNavigator(
                    viewModel = viewModel,
                    onRequestPermissions = ::checkAndRequestPermissions,
                    onDeleteItems = ::requestDeleteMedia
                )
            }
        }


        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.loadLocalMedia()
        } else {
            permissionLauncher.launch(permissions)
        }
    }
}

// Refined color palette — deep slate + electric blue accent
val DarkBg     = Color(0xFF0A0F1E)
val CardBg     = Color(0xFF141B2D)
val SurfaceBg  = Color(0xFF1C2539)
val TextPrimary   = Color(0xFFEFF2F8)
val TextSecondary = Color(0xFF7A8BAD)
val NeonAccent    = Color(0xFF4D9FFF)   // softer electric blue
val BackupGreen   = Color(0xFF34C759)
val BackupFailed  = Color(0xFFFF453A)

@Composable
fun TeleCloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBg,
            surface = CardBg,
            primary = NeonAccent,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigator(
    viewModel: PhotosViewModel,
    onRequestPermissions: () -> Unit,
    onDeleteItems: (List<MediaItem>) -> Unit
) {
    val networkClient = viewModel.getNetworkClient()
    var currentScreen by remember { mutableStateOf(if (networkClient.getServerUrl() == null || !networkClient.hasCredentials()) "server_setup" else "gallery") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBg
    ) {
        when (currentScreen) {
            "server_setup" -> {
                ServerSetupScreen(
                    viewModel = viewModel,
                    onSuccess = { currentScreen = "gallery" }
                )
            }
            "gallery" -> {
                MainHubScreen(
                    viewModel = viewModel,
                    onNavigateToSetup = { currentScreen = "server_setup" },
                    onRequestPermissions = onRequestPermissions,
                    onDeleteItems = onDeleteItems
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupScreen(
    viewModel: PhotosViewModel,
    onSuccess: () -> Unit
) {
    var urlText by remember { mutableStateOf(viewModel.getNetworkClient().getServerUrl() ?: "http://") }
    var usernameText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    val connectionState by viewModel.connectionState.collectFlow()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .background(DarkBg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = "Share Icon",
            tint = NeonAccent,
            modifier = Modifier.size(96.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "TeleCloud Photos",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "Enter your server URL and login credentials",
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.100:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonAccent,
                unfocusedBorderColor = TextSecondary,
                focusedLabelColor = NeonAccent,
                unfocusedLabelColor = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = usernameText,
            onValueChange = { usernameText = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonAccent,
                unfocusedBorderColor = TextSecondary,
                focusedLabelColor = NeonAccent,
                unfocusedLabelColor = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = passwordText,
            onValueChange = { passwordText = it },
            label = { Text("Password") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonAccent,
                unfocusedBorderColor = TextSecondary,
                focusedLabelColor = NeonAccent,
                unfocusedLabelColor = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.loginAndConnect(urlText, usernameText, passwordText) { success ->
                    if (success) {
                        Toast.makeText(context, "Connected successfully!", Toast.LENGTH_SHORT).show()
                        onSuccess()
                    } else {
                        Toast.makeText(context, "Connection or Login failed.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonAccent)
        ) {
            Text("Connect", color = DarkBg, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = connectionState) {
            is ConnectionState.Checking -> {
                CircularProgressIndicator(color = NeonAccent)
            }
            is ConnectionState.Failed -> {
                Text(
                    text = "Connection Failed: ${state.error}",
                    color = BackupFailed,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
            is ConnectionState.Connected -> {
                Text(
                    text = "Connected!",
                    color = BackupGreen,
                    fontSize = 14.sp
                )
            }
            else -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHubScreen(
    viewModel: PhotosViewModel,
    onNavigateToSetup: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDeleteItems: (List<MediaItem>) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val galleryGridState = rememberLazyGridState()
    val items by viewModel.mediaItems.collectFlow()
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val albums = remember(items) {
        items.groupBy { it.relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Camera" } }
            .map { (name, mediaItems) -> Triple(name, mediaItems, mediaItems.first()) }
            .sortedByDescending { it.second.size }
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBg)
        ) {
            // Screen content
            when (selectedTab) {
                0 -> GalleryScreen(
                    viewModel = viewModel,
                    gridState = galleryGridState,
                    onRequestPermissions = onRequestPermissions,
                    allItems = items,
                    isSearchActive = isSearchActive,
                    onSearchActiveChange = { isSearchActive = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onDeleteItems = onDeleteItems,
                    selectedIds = selectedIds,
                    onSelectedIdsChange = { selectedIds = it },
                    onAvatarClick = { selectedTab = 2 }
                )
                1 -> AlbumsScreen(albums = albums, viewModel = viewModel, onDeleteItems = onDeleteItems)
                2 -> TaoScreen(viewModel = viewModel, onNavigateToSetup = onNavigateToSetup)
            }

            // Floating Bottom Bar Container (matches image exactly)
            if (selectedIds.isEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: capsule shape tabs (Ảnh, Bộ sưu tập, Cài đặt)
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF1D2230).copy(alpha = 0.95f), RoundedCornerShape(32.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple(Icons.Default.Photo, "Ảnh", 0),
                            Triple(Icons.Default.CollectionsBookmark, "Bộ sưu tập", 1),
                            Triple(Icons.Default.Settings, "Cài đặt", 2)
                        ).forEach { (icon, label, idx) ->
                            val selected = selectedTab == idx
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(if (selected) Color(0xFF1E88E5) else Color.Transparent)
                                    .clickable { selectedTab = idx }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (selected) Color.White else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = label,
                                    color = if (selected) Color.White else TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Right: circular Search button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xFF1D2230).copy(alpha = 0.95f), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                selectedTab = 0
                                isSearchActive = !isSearchActive
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Tìm kiếm",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

enum class SortOrder { DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC }

// Sealed entry type — required for proper LazyGrid DSL with span
sealed class GalleryEntry {
    data class Header(val date: String) : GalleryEntry()
    data class Media(val item: MediaItem) : GalleryEntry()
}

fun formatDateHeader(epochSeconds: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(epochSeconds * 1000))
        else ->
            SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))
    }
}

// Returns a flat list of GalleryEntry (Header + Media) ready for items() DSL
fun buildGalleryEntries(items: List<MediaItem>, grouped: Boolean): List<GalleryEntry> {
    if (!grouped) return items.map { GalleryEntry.Media(it) }
    val result = mutableListOf<GalleryEntry>()
    var lastHeader = ""
    items.forEach { item ->
        val header = formatDateHeader(item.dateModified)
        if (header != lastHeader) {
            result.add(GalleryEntry.Header(header))
            lastHeader = header
        }
        result.add(GalleryEntry.Media(item))
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: PhotosViewModel,
    gridState: LazyGridState,
    onRequestPermissions: () -> Unit,
    allItems: List<MediaItem> = emptyList(),
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDeleteItems: (List<MediaItem>) -> Unit,
    selectedIds: Set<Long>,
    onSelectedIdsChange: (Set<Long>) -> Unit,
    onAvatarClick: () -> Unit
) {
    val items by viewModel.mediaItems.collectFlow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddMenu by remember { mutableStateOf(false) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var selectedItemForDetail by remember { mutableStateOf<MediaItem?>(null) }
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    var activeFilter by remember { mutableStateOf("All") }
    var gridColumns by remember { mutableStateOf(3) }
    var sortOrder by remember { mutableStateOf(SortOrder.DATE_DESC) }
    var expandedSortMenu by remember { mutableStateOf(false) }
    val isSelectionMode = selectedIds.isNotEmpty()


    // Pinch-to-zoom: iOS-style – animate actual cell size so grid reflows naturally
    // gridColumns: 1=large, 2=medium, 3=default, 4=small, 5=xs, 6=xxs
    val baseCellSizeDp = when (gridColumns) {
        1 -> 320f
        2 -> 180f
        3 -> 120f
        4 -> 90f
        5 -> 72f
        6 -> 58f
        else -> 120f
    }
    var pinchRawScale by remember { mutableStateOf(1f) }
    val targetCellDp = (baseCellSizeDp * pinchRawScale).coerceIn(48f, 380f)
    val animatedCellSizeDp by animateDpAsState(
        targetValue = targetCellDp.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cellSize"
    )

    // Collapsible toolbar: track scroll direction
    var toolbarVisible by remember { mutableStateOf(true) }
    var prevScrollOffset by remember { mutableStateOf(0) }
    var prevScrollIndex by remember { mutableStateOf(0) }
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) {
        val idx = gridState.firstVisibleItemIndex
        val off = gridState.firstVisibleItemScrollOffset
        // At top always show toolbar
        if (idx == 0 && off < 10) {
            toolbarVisible = true
        } else if (idx < prevScrollIndex || (idx == prevScrollIndex && off < prevScrollOffset)) {
            // scrolling up -> show
            toolbarVisible = true
        } else if (idx > prevScrollIndex || (idx == prevScrollIndex && off > prevScrollOffset + 30)) {
            // scrolling down -> hide
            toolbarVisible = false
        }
        prevScrollIndex = idx
        prevScrollOffset = off
    }

    // Backup stats for header
    val backedUp = remember(items) { items.count { it.backupStatus == BackupStatus.BACKED_UP } }

    val sortedItems = remember(items, activeFilter, searchQuery, sortOrder) {
        var result = when (activeFilter) {
            "Photos" -> items.filter { !it.isVideo }
            "Videos" -> items.filter { it.isVideo }
            else -> items
        }
        if (searchQuery.isNotBlank()) {
            result = result.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
        when (sortOrder) {
            SortOrder.DATE_DESC -> result.sortedByDescending { it.dateModified }
            SortOrder.DATE_ASC  -> result.sortedBy { it.dateModified }
            SortOrder.SIZE_DESC -> result.sortedByDescending { it.size }
            SortOrder.SIZE_ASC  -> result.sortedBy { it.size }
        }
    }

    val galleryEntries = remember(sortedItems, sortOrder) {
        val grouped = sortOrder == SortOrder.DATE_DESC || sortOrder == SortOrder.DATE_ASC
        buildGalleryEntries(sortedItems, grouped)
    }

    val itemsByHeader = remember(sortedItems) {
        sortedItems.groupBy { formatDateHeader(it.dateModified) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top App Bar (collapsible on scroll) ──────────────────────
            AnimatedVisibility(
                visible = !isSelectionMode && !isSearchActive && toolbarVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
            if (!isSelectionMode && !isSearchActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(CardBg, DarkBg)
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Pill badge for backup status (toggles auto backup on click)
                        val autoBackup by viewModel.autoBackupEnabled.collectFlow()
                        val allTasks by viewModel.uploadTasks.collectFlow()
                        val activeTasks = remember(allTasks) { allTasks.filter { it.uploadState != "COMPLETED" } }

                        Row(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                                .clickable {
                                    viewModel.setAutoBackupEnabled(!autoBackup)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!autoBackup) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else if (activeTasks.isNotEmpty()) {
                                CircularProgressIndicator(
                                    color = NeonAccent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = BackupGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            val statusText = when {
                                !autoBackup -> "Đang tắt sao lưu"
                                activeTasks.isNotEmpty() -> "Đang tải: còn ${activeTasks.size} mục"
                                else -> "Đã sao lưu"
                            }

                            Text(
                                text = statusText,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Right: Add (+), Notification bell, and user avatar with rainbow border
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Thêm",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable {
                                            showAddMenu = true
                                        }
                                )
                                DropdownMenu(
                                    expanded = showAddMenu,
                                    onDismissRequest = { showAddMenu = false },
                                    modifier = Modifier.background(SurfaceBg)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Tạo album mới", color = TextPrimary, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, tint = NeonAccent, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            showAddMenu = false
                                            showCreateAlbumDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sao lưu ảnh thủ công", color = TextPrimary, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, null, tint = NeonAccent, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            showAddMenu = false
                                            if (selectedIds.isNotEmpty()) {
                                                sortedItems.filter { it.id in selectedIds }.forEach { viewModel.uploadItem(it) }
                                                onSelectedIdsChange(emptySet())
                                                Toast.makeText(context, "Đã thêm các ảnh đã chọn vào hàng chờ sao lưu", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Vui lòng chọn ảnh trước (ấn giữ) để tải lên", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.NotificationsNone,
                                contentDescription = "Thông báo",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        // Notifications action
                                    }
                            )
                            // Avatar circular with colorful border matching image
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .border(
                                        2.dp,
                                        Brush.sweepGradient(
                                            listOf(
                                                Color.Red,
                                                Color.Yellow,
                                                Color.Green,
                                                Color.Blue,
                                                Color.Red
                                            )
                                        ),
                                        RoundedCornerShape(17.dp)
                                    )
                                    .clip(RoundedCornerShape(17.dp))
                                    .clickable { onAvatarClick() }
                                    .padding(2.dp)
                                    .background(Color.Gray, RoundedCornerShape(15.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "T",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Thin backup progress bar
                    if (items.isNotEmpty()) {
                        val pct = backedUp.toFloat() / items.size
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(SurfaceBg)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(pct)
                                    .background(
                                        Brush.horizontalGradient(listOf(NeonAccent, BackupGreen))
                                    )
                            )
                        }
                    }

                    // Combined Filter pills row + layout & sort actions (Clean and space-efficient)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(SurfaceBg, RoundedCornerShape(20.dp))
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            listOf("Tất cả", "Ảnh", "Video").forEach { cat ->
                                val catKey = when (cat) { "Ảnh" -> "Photos"; "Video" -> "Videos"; else -> "All" }
                                val sel = activeFilter == catKey
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (sel) NeonAccent else Color.Transparent)
                                        .clickable { activeFilter = catKey }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        cat,
                                        fontSize = 12.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) DarkBg else TextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Layout and Sort tools
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { gridColumns = when (gridColumns) { 3->4; 4->5; 5->6; 6->2; 2->1; else->3 } },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    if (gridColumns == 1) Icons.Default.ViewList else Icons.Default.GridView,
                                    null, tint = TextSecondary, modifier = Modifier.size(18.dp)
                                )
                            }
                            Box {
                                IconButton(onClick = { expandedSortMenu = true }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Default.Sort, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                                DropdownMenu(
                                    expanded = expandedSortMenu,
                                    onDismissRequest = { expandedSortMenu = false },
                                    modifier = Modifier.background(SurfaceBg)
                                ) {
                                    listOf(
                                        "Mới nhất" to SortOrder.DATE_DESC,
                                        "Cũ nhất" to SortOrder.DATE_ASC,
                                        "Lớn nhất" to SortOrder.SIZE_DESC,
                                        "Nhỏ nhất" to SortOrder.SIZE_ASC
                                    ).forEach { (label, order) ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (sortOrder == order) {
                                                        Icon(Icons.Default.Check, null, tint = NeonAccent, modifier = Modifier.size(14.dp))
                                                        Spacer(Modifier.width(6.dp))
                                                    } else Spacer(Modifier.width(20.dp))
                                                    Text(label, color = if (sortOrder == order) NeonAccent else TextPrimary, fontSize = 13.sp)
                                                }
                                            },
                                            onClick = { sortOrder = order; expandedSortMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // end AnimatedVisibility toolbar

            // ── Search bar (slides in when active) ──────────────────────
            AnimatedVisibility(visible = isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = { Text("Tìm kiếm...", color = TextSecondary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = NeonAccent) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, null, tint = TextSecondary)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonAccent,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.25f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = NeonAccent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }


            // ── Selection mode bar ───────────────────────────────────────
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NeonAccent.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onSelectedIdsChange(emptySet()) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "${selectedIds.size} đã chọn",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp
                    )
                    TextButton(onClick = { onSelectedIdsChange(sortedItems.map { it.id }.toSet()) }) {
                        Text("Chọn tất cả", color = NeonAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }

            if (sortedItems.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.35f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        when {
                            searchQuery.isNotBlank() -> "No results for \"$searchQuery\""
                            items.isEmpty() -> "No photos or videos found"
                            else -> "No items match this filter"
                        },
                        color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center
                    )
                    if (items.isEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onRequestPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonAccent),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Grant Permissions", color = DarkBg, fontWeight = FontWeight.Bold) }
                    }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = animatedCellSizeDp),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .pointerInput(Unit) {
                            // 2-finger-only pinch: does NOT block single-finger scroll
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val evt = awaitPointerEvent(PointerEventPass.Initial)
                                    if (evt.changes.size >= 2) {
                                        val zoom = evt.calculateZoom()
                                        if (zoom != 1f) {
                                            pinchRawScale = (pinchRawScale * zoom).coerceIn(0.5f, 2.0f)
                                            var colChanged = false
                                            if (pinchRawScale > 1.3f) {
                                                if (gridColumns > 1) {
                                                    gridColumns--
                                                    colChanged = true
                                                }
                                            } else if (pinchRawScale < 0.77f) {
                                                if (gridColumns < 6) {
                                                    gridColumns++
                                                    colChanged = true
                                                }
                                            }
                                            if (colChanged) {
                                                pinchRawScale = 1f
                                            }
                                            evt.changes.forEach { ch -> if (ch.positionChange() != Offset.Zero) ch.consume() }
                                        }
                                    }
                                } while (evt.changes.any { ch -> ch.pressed })
                                if (pinchRawScale != 1f) pinchRawScale = 1f
                            }
                        },
                    contentPadding = PaddingValues(
                        bottom = if (isSelectionMode) 100.dp else 90.dp, // Extra padding to avoid overlapping the floated bottom bar
                        start = 3.dp, end = 3.dp, top = 3.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (!isSelectionMode && !isSearchActive && sortOrder == SortOrder.DATE_DESC) {
                        item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                            FeaturedMemoriesCarousel(sortedItems, viewModel.getNetworkClient())
                        }
                    }

                    items(
                        count = galleryEntries.size,
                        key = { idx ->
                            when (val e = galleryEntries[idx]) {
                                is GalleryEntry.Header -> "hdr_${e.date}"
                                is GalleryEntry.Media  -> e.item.id
                            }
                        },
                        span = { idx ->
                            when (galleryEntries[idx]) {
                                is GalleryEntry.Header -> GridItemSpan(maxCurrentLineSpan)
                                is GalleryEntry.Media  -> GridItemSpan(1)
                            }
                        }
                    ) { idx ->
                        when (val entry = galleryEntries[idx]) {
                            is GalleryEntry.Header -> {
                                DateSectionHeader(
                                    date = entry.date,
                                    allItemsInGroup = itemsByHeader[entry.date] ?: emptyList(),
                                    selectedIds = selectedIds,
                                    onGroupSelect = { groupItems ->
                                        val groupIds = groupItems.map { it.id }.toSet()
                                        onSelectedIdsChange(if (groupIds.all { it in selectedIds })
                                            selectedIds - groupIds else selectedIds + groupIds)
                                    }
                                )
                            }
                            is GalleryEntry.Media -> {
                                val item = entry.item
                                if (gridColumns == 1) {
                                    GalleryListItem(
                                        item = item,
                                        networkClient = viewModel.getNetworkClient(),
                                        onClick = { if (isSelectionMode) onSelectedIdsChange(selectedIds.toggle(item.id)) else selectedItemForDetail = item },
                                        onUploadClick = { viewModel.uploadItem(item) },
                                        isSelected = item.id in selectedIds,
                                        isSelectionMode = isSelectionMode,
                                        onLongClick = { onSelectedIdsChange(selectedIds + item.id) }
                                    )
                                } else {
                                    GalleryGridItem(
                                        item = item,
                                        networkClient = viewModel.getNetworkClient(),
                                        onClick = { if (isSelectionMode) onSelectedIdsChange(selectedIds.toggle(item.id)) else selectedItemForDetail = item },
                                        onUploadClick = { viewModel.uploadItem(item) },
                                        columns = gridColumns,
                                        isSelected = item.id in selectedIds,
                                        isSelectionMode = isSelectionMode,
                                        onLongClick = { onSelectedIdsChange(selectedIds + item.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isSelectionMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SelectionActionBar(
                selectedCount = selectedIds.size,
                onUpload = {
                    viewModel.uploadItems(sortedItems.filter { it.id in selectedIds })
                    onSelectedIdsChange(emptySet())
                },
                onDelete = {
                    val itemsToDelete = sortedItems.filter { it.id in selectedIds }
                    onDeleteItems(itemsToDelete)
                    onSelectedIdsChange(emptySet())
                },
                onCancel = { onSelectedIdsChange(emptySet()) }
            )
        }
    }

    selectedItemForDetail?.let { item ->
        val initialIndex = sortedItems.indexOf(item).coerceAtLeast(0)
        DetailDialog(
            items = sortedItems,
            initialIndex = initialIndex,
            networkClient = viewModel.getNetworkClient(),
            onDismiss = { selectedItemForDetail = null },
            onUpload = { targetItem -> viewModel.uploadItem(targetItem) },
            onDelete = { targetItem ->
                onDeleteItems(listOf(targetItem))
                selectedItemForDetail = null
            }
        )
    }

    if (showCreateAlbumDialog) {
        val context = LocalContext.current
        var newAlbumName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateAlbumDialog = false },
            title = { Text("Tạo album mới", color = TextPrimary) },
            text = {
                Column {
                    Text("Nhập tên album/thư mục mới:", color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonAccent,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newAlbumName.isNotBlank()) {
                            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                            val newDir = java.io.File(picturesDir, newAlbumName)
                            val success = newDir.mkdirs()
                            if (success || newDir.exists()) {
                                Toast.makeText(context, "Đã tạo thư mục album: $newAlbumName", Toast.LENGTH_SHORT).show()
                                if (selectedIds.isNotEmpty()) {
                                    val selectedItems = sortedItems.filter { it.id in selectedIds }
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        selectedItems.forEach { item ->
                                            try {
                                                val inputStream = context.contentResolver.openInputStream(Uri.parse(item.contentUri))
                                                val targetFile = java.io.File(newDir, item.displayName)
                                                inputStream?.use { input ->
                                                    targetFile.outputStream().use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                android.media.MediaScannerConnection.scanFile(
                                                    context,
                                                    arrayOf(targetFile.absolutePath),
                                                    null,
                                                    null
                                                )
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                        viewModel.loadLocalMedia()
                                    }
                                    onSelectedIdsChange(emptySet())
                                } else {
                                    android.media.MediaScannerConnection.scanFile(
                                        context,
                                        arrayOf(newDir.absolutePath),
                                        null,
                                        null
                                    )
                                    viewModel.loadLocalMedia()
                                }
                            } else {
                                Toast.makeText(context, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showCreateAlbumDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonAccent)
                ) {
                    Text("Tạo", color = DarkBg, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbumDialog = false }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = CardBg
        )
    }
}


// ─────────────────────────────────────────────────────────────
// Featured Memories Carousel (Google Photos style)
// ─────────────────────────────────────────────────────────────
@Composable
fun FeaturedMemoriesCarousel(items: List<MediaItem>, networkClient: NetworkClient) {
    val context = LocalContext.current
    val memoryItems = remember(items) {
        val list = mutableListOf<Pair<String, MediaItem?>>()
        // Video nổi bật
        val video = items.firstOrNull { it.isVideo } ?: items.firstOrNull()
        list.add("Video nổi bật" to video)
        
        // Hoa khoe sắc
        val secondItem = items.getOrNull(1) ?: items.firstOrNull()
        list.add("Hoa khoe sắc\nNăm tháng trôi qua" to secondItem)

        // Khoảnh khắc đáng nhớ
        val thirdItem = items.getOrNull(2) ?: items.firstOrNull()
        list.add("Xem lại khoảnh khắc\nĐáng nhớ nhất" to thirdItem)
        list
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(memoryItems.size) { idx ->
                val (title, media) = memoryItems[idx]
                Card(
                    modifier = Modifier
                        .width(140.dp)
                        .height(200.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (media != null) {
                            val (modelData, headers) = getMediaSource(media, networkClient)
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(modelData)
                                    .apply {
                                        headers?.forEach { (k, v) -> addHeader(k, v) }
                                        if (media.isVideo) {
                                            decoderFactory(sharedVideoFrameDecoderFactory)
                                        }
                                    }
                                    .crossfade(true)
                                    .size(280, 400)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .build(),
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFFE91E63), Color(0xFF9C27B0))
                                        )
                                    )
                            )
                        }
                        
                        // Dark overlay gradient for readability
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                    )
                                )
                        )

                        // Title Text overlay
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Tao Screen (Create tab: combines active Uploads + Settings)
// ─────────────────────────────────────────────────────────────
@Composable
fun TaoScreen(
    viewModel: PhotosViewModel,
    onNavigateToSetup: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 120.dp) // Avoid overlapping the floating capsule bar
    ) {
        Text(
            text = "Cài đặt & Sao lưu",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        // 1. Upload queue status card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Hàng chờ sao lưu",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Xem tiến trình và quản lý các tệp đang được sao lưu lên đám mây.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                val allTasks by viewModel.uploadTasks.collectFlow()
                val activeTasks = remember(allTasks) { allTasks.filter { it.uploadState != "COMPLETED" } }
                if (activeTasks.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tất cả ảnh/video đã được đồng bộ.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = BackupGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Text(
                        text = "Có ${activeTasks.size} tác vụ đang chờ/tải lên...",
                        color = NeonAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    val progressMap by org.telecloud.photos.uploader.UploadProgressTracker.progressMap.collectFlow()
                    activeTasks.take(2).forEach { task ->
                        val progressInfo = progressMap[task.id]
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(task.filename, color = TextPrimary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(task.uploadState, color = NeonAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            if (task.uploadState == "UPLOADING" && progressInfo != null) {
                                val frac = if (progressInfo.totalSize > 0) progressInfo.bytesUploaded.toFloat() / progressInfo.totalSize else 0f
                                LinearProgressIndicator(
                                    progress = frac.coerceIn(0f, 1f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = NeonAccent,
                                    trackColor = DarkBg.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    if (activeTasks.size > 2) {
                        Text(
                            text = "...và ${activeTasks.size - 2} tác vụ khác",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { viewModel.clearAllTasks() }
                        ) {
                            Text("Xóa hàng chờ", color = BackupFailed, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Backup setup card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            val networkClient = viewModel.getNetworkClient()
            val context = LocalContext.current
            var wifiOnly by remember { mutableStateOf(networkClient.isWifiOnly()) }
            var backupFolder by remember { mutableStateOf(networkClient.getBackupFolder()) }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Cấu hình sao lưu",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Chỉ sao lưu qua Wi-Fi", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Chỉ tải ảnh lên khi kết nối với mạng Wi-Fi.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { checked ->
                            wifiOnly = checked
                            networkClient.setWifiOnly(checked)
                            viewModel.triggerWorkManagerBackup()
                            Toast.makeText(context, "Đã cập nhật Wi-Fi", Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonAccent,
                            checkedTrackColor = NeonAccent.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = TextSecondary.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(16.dp))

                Text("Thư mục sao lưu đám mây", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("Đường dẫn thư mục mục tiêu trên máy chủ TeleCloud.", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = backupFolder,
                        onValueChange = { backupFolder = it },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonAccent,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            networkClient.setBackupFolder(backupFolder)
                            Toast.makeText(context, "Đã lưu thư mục", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Lưu", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = TextSecondary.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(16.dp))

                Text("Thông tin máy chủ", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(networkClient.getServerUrl() ?: "Không tìm thấy URL máy chủ", color = TextSecondary, fontSize = 11.sp)
                
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        networkClient.clearAll()
                        Toast.makeText(context, "Đã đăng xuất và reset thiết lập", Toast.LENGTH_SHORT).show()
                        onNavigateToSetup()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BackupFailed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Đăng xuất & Đặt lại thiết lập", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id


// ─────────────────────────────────────────────────────────────
// Albums Screen
// ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    albums: List<Triple<String, List<MediaItem>, MediaItem>>,
    viewModel: PhotosViewModel,
    onDeleteItems: (List<MediaItem>) -> Unit
) {
    val albumGridState = rememberLazyGridState()
    var selectedAlbum by remember { mutableStateOf<Triple<String, List<MediaItem>, MediaItem>?>(null) }
    var albumToDelete by remember { mutableStateOf<Triple<String, List<MediaItem>, MediaItem>?>(null) }

    selectedAlbum?.let { (name, albumItems, _) ->
        // Show album detail as a mini gallery
        var selectedIds by remember { mutableStateOf(setOf<Long>()) }
        val isSelectionMode = selectedIds.isNotEmpty()
        var selectedItemForDetail by remember { mutableStateOf<MediaItem?>(null) }
        var infoItem by remember { mutableStateOf<MediaItem?>(null) }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Album header (Switches to selection header if selecting)
                if (isSelectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeonAccent.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            "${selectedIds.size} đã chọn",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp
                        )
                        TextButton(onClick = { selectedIds = albumItems.map { it.id }.toSet() }) {
                            Text("Chọn tất cả", color = NeonAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedAlbum = null }) {
                            Icon(Icons.Default.ArrowBack, null, tint = TextPrimary)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("${albumItems.size} mục", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // Grid of album items
                val albumGridState2 = rememberLazyGridState()
                val albumEntries = remember(albumItems) { albumItems.map { GalleryEntry.Media(it) } }
                LazyVerticalGrid(
                    state = albumGridState2,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(bottom = if (isSelectionMode) 100.dp else 16.dp, start = 3.dp, end = 3.dp, top = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(albumEntries.size, key = { (albumEntries[it] as GalleryEntry.Media).item.id }) { idx ->
                        val item = (albumEntries[idx] as GalleryEntry.Media).item
                        GalleryGridItem(
                            item = item,
                            networkClient = viewModel.getNetworkClient(),
                            onClick = {
                                if (isSelectionMode) {
                                    selectedIds = selectedIds.toggle(item.id)
                                } else {
                                    selectedItemForDetail = item
                                }
                            },
                            onUploadClick = { viewModel.uploadItem(item) },
                            columns = 3,
                            isSelected = item.id in selectedIds,
                            isSelectionMode = isSelectionMode,
                            onLongClick = {
                                selectedIds = selectedIds + item.id
                            }
                        )
                    }
                }
            }

            // Floating SelectionActionBar
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                SelectionActionBar(
                    selectedCount = selectedIds.size,
                    onUpload = {
                        viewModel.uploadItems(albumItems.filter { it.id in selectedIds })
                        selectedIds = emptySet()
                    },
                    onDelete = {
                        val itemsToDelete = albumItems.filter { it.id in selectedIds }
                        onDeleteItems(itemsToDelete)
                        selectedIds = emptySet()
                    },
                    onCancel = { selectedIds = emptySet() }
                )
            }

            selectedItemForDetail?.let { item ->
                val initialIndex = albumItems.indexOf(item).coerceAtLeast(0)
                DetailDialog(
                    items = albumItems,
                    initialIndex = initialIndex,
                    networkClient = viewModel.getNetworkClient(),
                    onDismiss = { selectedItemForDetail = null },
                    onUpload = { targetItem -> viewModel.uploadItem(targetItem) },
                    onDelete = { targetItem ->
                        onDeleteItems(listOf(targetItem))
                        selectedItemForDetail = null
                    }
                )
            }
        }
        return
    }

    if (albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CollectionsBookmark, null,
                    tint = TextSecondary.copy(alpha = 0.35f), modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text("No albums found", color = TextSecondary, fontSize = 15.sp)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Albums",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        LazyVerticalGrid(
            state = albumGridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(albums.size, key = { albums[it].first }) { idx ->
                val (name, albumItems, cover) = albums[idx]
                val backedUpCount = albumItems.count { it.backupStatus == BackupStatus.BACKED_UP }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { selectedAlbum = albums[idx] },
                            onLongClick = { albumToDelete = albums[idx] }
                        ),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box {
                        val (modelData, headers) = getMediaSource(cover, viewModel.getNetworkClient())
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(modelData)
                                .apply {
                                    headers?.forEach { (k, v) -> addHeader(k, v) }
                                    if (cover.isVideo) {
                                        decoderFactory(sharedVideoFrameDecoderFactory)
                                    }
                                }
                                .crossfade(true).size(400)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        )
                        // Gradient overlay
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                    )
                                )
                        )
                        // Item count badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("${albumItems.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            name,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (backedUpCount == albumItems.size) Icons.Default.Cloud else Icons.Default.CloudOff,
                                null,
                                tint = if (backedUpCount == albumItems.size) BackupGreen else TextSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                "$backedUpCount/${albumItems.size} backed up",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    albumToDelete?.let { (name, albumItems, _) ->
        AlertDialog(
            onDismissRequest = { albumToDelete = null },
            title = { Text("Xóa album này?", color = TextPrimary) },
            text = { Text("Tất cả ${albumItems.size} ảnh/video trong album '$name' sẽ bị xóa khỏi bộ nhớ thiết bị của bạn. Hành động này không thể hoàn tác.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteItems(albumItems)
                        albumToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BackupFailed)
                ) {
                    Text("Xóa tất cả", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { albumToDelete = null }) {
                    Text("Hủy", color = TextSecondary)
                }
            },
            containerColor = CardBg
        )
    }
}

@Composable
fun DateSectionHeader(
    date: String,
    allItemsInGroup: List<MediaItem>,
    selectedIds: Set<Long>,
    onGroupSelect: (List<MediaItem>) -> Unit
) {
    val groupIds = allItemsInGroup.map { it.id }.toSet()
    val allSelected = groupIds.isNotEmpty() && groupIds.all { it in selectedIds }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .clickable { onGroupSelect(allItemsInGroup) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = date,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        if (allSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Group selected",
                tint = NeonAccent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SelectionActionBar(
    selectedCount: Int,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Đã chọn $selectedCount mục",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Delete Button (Trash icon)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.background(BackupFailed.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = BackupFailed, modifier = Modifier.size(20.dp))
                }
                
                // Cancel
                OutlinedButton(
                    onClick = onCancel,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Hủy", color = TextSecondary, fontSize = 13.sp)
                }
                
                // Upload
                Button(
                    onClick = onUpload,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonAccent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = DarkBg)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sao lưu", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoInfoBottomSheet(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("File Info", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(16.dp))

            InfoRow(icon = Icons.Default.Description, label = "Name", value = item.displayName)
            InfoRow(icon = Icons.Default.AspectRatio, label = "Resolution", value = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else "Unknown")
            InfoRow(icon = Icons.Default.Storage, label = "Size", value = formatFileSize(item.size))
            InfoRow(icon = Icons.Default.CalendarToday, label = "Date",
                value = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(item.dateModified * 1000)))
            InfoRow(icon = Icons.Default.Folder, label = "Path", value = item.relativePath)
            InfoRow(icon = Icons.Default.Category, label = "Type", value = item.mimeType)
            InfoRow(
                icon = if (item.backupStatus == BackupStatus.BACKED_UP) Icons.Default.Cloud else Icons.Default.CloudOff,
                label = "Backup",
                value = when (item.backupStatus) {
                    BackupStatus.BACKED_UP -> "Backed up to cloud"
                    BackupStatus.UPLOADING -> "Uploading..."
                    BackupStatus.QUEUED -> "Queued for upload"
                    BackupStatus.FAILED -> "Upload failed"
                    else -> "Not backed up"
                },
                valueColor = when (item.backupStatus) {
                    BackupStatus.BACKED_UP -> BackupGreen
                    BackupStatus.FAILED -> BackupFailed
                    else -> TextSecondary
                }
            )
        }
    }
}

@Composable
fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color = TextSecondary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = NeonAccent, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Divider(color = TextSecondary.copy(alpha = 0.1f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGridItem(
    item: MediaItem,
    networkClient: NetworkClient,
    onClick: (MediaItem) -> Unit,
    onUploadClick: (MediaItem) -> Unit,
    columns: Int,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val thumbnailSize = when (columns) {
        1 -> 480
        2 -> 320
        3 -> 240
        4 -> 180
        5 -> 140
        6 -> 100
        else -> 200
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = onLongClick
            )
    ) {
        val (modelData, headers) = remember(item.id, item.contentUri) {
            getMediaUrlSource(item, "thumbnail", networkClient)
        }
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(modelData)
                .apply {
                    headers?.forEach { (k, v) -> addHeader(k, v) }
                    if (item.isVideo) {
                        decoderFactory(sharedVideoFrameDecoderFactory)
                    }
                }
                .crossfade(true)
                .size(thumbnailSize)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Selection overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NeonAccent.copy(alpha = 0.3f))
            )
        }

        // Checkbox (top-left when in selection mode)
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) NeonAccent else Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = DarkBg, modifier = Modifier.size(14.dp))
                }
            }
        } else {
            // Normal cloud status icon (top-right)
            val showCloudIcon = when (item.backupStatus) {
                BackupStatus.BACKED_UP -> true
                BackupStatus.UPLOADING -> true
                BackupStatus.QUEUED -> true
                BackupStatus.NOT_BACKED_UP -> true
                BackupStatus.FAILED -> true
                else -> false
            }
            if (showCloudIcon) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable {
                            if (item.backupStatus == BackupStatus.NOT_BACKED_UP || item.backupStatus == BackupStatus.FAILED) {
                                onUploadClick(item)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when (item.backupStatus) {
                        BackupStatus.BACKED_UP -> {
                            // Show check icon (CloudDone) for both LOCAL_AND_CLOUD and CLOUD_ONLY
                            Icon(Icons.Default.CloudDone, null, tint = BackupGreen, modifier = Modifier.size(10.dp))
                        }
                        BackupStatus.UPLOADING -> CircularProgressIndicator(color = NeonAccent, strokeWidth = 1.dp, modifier = Modifier.size(10.dp))
                        BackupStatus.QUEUED -> Icon(Icons.Default.Refresh, null, tint = NeonAccent, modifier = Modifier.size(10.dp))
                        BackupStatus.FAILED -> Icon(Icons.Default.CloudOff, null, tint = Color.Red, modifier = Modifier.size(10.dp))
                        BackupStatus.NOT_BACKED_UP -> {
                            // Show outline icon (CloudQueue) for LOCAL_ONLY
                            Icon(Icons.Default.CloudQueue, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(10.dp))
                        }
                        else -> {}
                    }
                }
            }
        }

        // Video Indicator Badge (bottom-left corner)
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(formatDuration(item.duration), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryListItem(
    item: MediaItem,
    networkClient: NetworkClient,
    onClick: (MediaItem) -> Unit,
    onUploadClick: (MediaItem) -> Unit,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) Modifier.border(2.dp, NeonAccent, RoundedCornerShape(8.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) NeonAccent.copy(alpha = 0.1f) else CardBg),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox or Thumbnail
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    val (modelData, headers) = remember(item.id, item.contentUri) {
                        getMediaUrlSource(item, "thumbnail", networkClient)
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(modelData)
                            .apply {
                                headers?.forEach { (k, v) -> addHeader(k, v) }
                                if (item.isVideo) {
                                    decoderFactory(sharedVideoFrameDecoderFactory)
                                }
                            }
                            .crossfade(true).size(150)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Selected overlay
                    if (isSelected) {
                        Box(modifier = Modifier.fillMaxSize().background(NeonAccent.copy(alpha = 0.4f)))
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    val (modelData, headers) = remember(item.id, item.contentUri) {
                        getMediaUrlSource(item, "thumbnail", networkClient)
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(modelData)
                            .apply {
                                headers?.forEach { (k, v) -> addHeader(k, v) }
                                if (item.isVideo) {
                                    decoderFactory(sharedVideoFrameDecoderFactory)
                                }
                            }
                            .crossfade(true).size(150)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.isVideo) {
                        Box(
                            modifier = Modifier.align(Alignment.BottomStart).padding(2.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                        ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                Spacer(modifier = Modifier.height(2.dp))
                Text("${formatFileSize(item.size)} · ${if (item.isVideo) "Video" else "Photo"}", color = TextSecondary, fontSize = 12.sp)
                Text(
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.dateModified * 1000)),
                    color = TextSecondary, fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!isSelectionMode && (
                item.backupStatus == BackupStatus.BACKED_UP ||
                item.backupStatus == BackupStatus.UPLOADING ||
                item.backupStatus == BackupStatus.QUEUED
            )) {
                IconButton(
                    onClick = {
                        if (item.backupStatus == BackupStatus.NOT_BACKED_UP || item.backupStatus == BackupStatus.FAILED) {
                            onUploadClick(item)
                        }
                    }
                ) {
                    when (item.backupStatus) {
                        BackupStatus.BACKED_UP -> Icon(Icons.Default.Cloud, null, tint = BackupGreen, modifier = Modifier.size(20.dp))
                        BackupStatus.UPLOADING -> CircularProgressIndicator(color = NeonAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        BackupStatus.QUEUED -> Icon(Icons.Default.Refresh, null, tint = NeonAccent, modifier = Modifier.size(18.dp))
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
fun UploadsScreen(viewModel: PhotosViewModel) {
    val allTasks by viewModel.uploadTasks.collectFlow()
    val tasks = remember(allTasks) {
        allTasks.filter { it.uploadState != "COMPLETED" }
    }
    val progressMap by org.telecloud.photos.uploader.UploadProgressTracker.progressMap.collectFlow()

    if (tasks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No upload tasks in progress.", color = TextSecondary)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Upload Queue (${tasks.size})",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Button(
                    onClick = { viewModel.clearAllTasks() },
                    colors = ButtonDefaults.buttonColors(containerColor = BackupFailed.copy(alpha = 0.8f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Clear Queue", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { it.id }) { task: org.telecloud.photos.data.UploadTask ->
                    val progressInfo = progressMap[task.id]

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, 
                            if (task.uploadState == "UPLOADING") NeonAccent.copy(alpha = 0.3f) else Color.Transparent
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = task.filename,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val stateBadgeColor = when (task.uploadState) {
                                    "QUEUED" -> TextSecondary
                                    "UPLOADING" -> NeonAccent
                                    "FAILED" -> BackupFailed
                                    else -> TextSecondary
                                }
                                Text(
                                    text = task.uploadState,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = stateBadgeColor,
                                    modifier = Modifier
                                        .background(stateBadgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            if (task.uploadState == "UPLOADING" && progressInfo != null) {
                                val progressFraction = if (progressInfo.totalSize > 0) {
                                    progressInfo.bytesUploaded.toFloat() / progressInfo.totalSize.toFloat()
                                } else 0f
                                
                                val percent = (progressFraction * 100).toInt()

                                LinearProgressIndicator(
                                    progress = progressFraction.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = NeonAccent,
                                    trackColor = DarkBg.copy(alpha = 0.5f)
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${formatFileSize(progressInfo.bytesUploaded)} / ${formatFileSize(progressInfo.totalSize)} ($percent%)",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    val speedStr = if (progressInfo.speedBytesPerSec > 0) {
                                        "${formatFileSize(progressInfo.speedBytesPerSec.toLong())}/s"
                                    } else "0 B/s"
                                    Text(
                                        text = speedStr,
                                        fontSize = 11.sp,
                                        color = NeonAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (progressInfo.etaSeconds > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Estimated Remaining: ${progressInfo.etaSeconds}s",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            } else {
                                // For QUEUED or FAILED tasks
                                Text(
                                    text = "Size: ${formatFileSize(task.totalSize)}",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }

                            task.lastError?.let {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Error: $it",
                                    fontSize = 11.sp,
                                    color = BackupFailed
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: PhotosViewModel,
    onNavigateToSetup: () -> Unit
) {
    val networkClient = viewModel.getNetworkClient()
    val context = LocalContext.current

    var wifiOnly by remember { mutableStateOf(networkClient.isWifiOnly()) }
    var backupFolder by remember { mutableStateOf(networkClient.getBackupFolder()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Section 1: Backup Preferences
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Backup Preferences", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))

                // Wifi-Only Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wi-Fi Only Backup", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            "Only upload photos when connected to Wi-Fi networks.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { checked ->
                            wifiOnly = checked
                            networkClient.setWifiOnly(checked)
                            // Re-trigger WorkManager with new constraints
                            viewModel.triggerWorkManagerBackup()
                            Toast.makeText(context, "Wi-Fi constraint updated", Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonAccent,
                            checkedTrackColor = NeonAccent.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Divider(color = TextSecondary.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(16.dp))

                // Cloud Backup Folder Path Input
                Text("Cloud Backup Directory", color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    "Set the target folder path on your TeleCloud server.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = backupFolder,
                        onValueChange = { backupFolder = it },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonAccent,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            networkClient.setBackupFolder(backupFolder)
                            backupFolder = networkClient.getBackupFolder() // Reload formatted path
                            Toast.makeText(context, "Cloud path saved to $backupFolder", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonAccent)
                    ) {
                        Text("Save", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 2: Connection details
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Connection Details", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Server URL: ${networkClient.getServerUrl() ?: "Not configured"}", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onNavigateToSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonAccent)
                ) {
                    Text("Change Server URL", color = DarkBg, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: Account Action
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Account Action", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        networkClient.clearAll()
                        Toast.makeText(context, "Logged out and settings reset", Toast.LENGTH_SHORT).show()
                        onNavigateToSetup()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BackupFailed)
                ) {
                    Text("Reset App", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailDialog(
    items: List<MediaItem>,
    initialIndex: Int,
    networkClient: NetworkClient,
    onDismiss: () -> Unit,
    onUpload: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit
) {
    var systemUiVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    val activeItem = items[pagerState.currentPage]

    // Drag-to-dismiss: track vertical drag
    var dragOffsetY by remember { mutableStateOf(0f) }
    val dismissThreshold = 200f
    val bgAlpha by animateFloatAsState(
        targetValue = (1f - (kotlin.math.abs(dragOffsetY) / (dismissThreshold * 2f))).coerceIn(0f, 1f),
        label = "bgAlpha"
    )

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffsetY
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var dy = 0f
                        do {
                            val evt = awaitPointerEvent()
                            if (evt.changes.size == 1) {
                                val drag = evt.changes[0].positionChange().y
                                dy += drag
                                dragOffsetY = dy
                                if (dy > 10f) {
                                    evt.changes.forEach { ch -> ch.consume() }
                                }
                            } else {
                                dy = 0f
                                dragOffsetY = 0f
                                break
                            }
                        } while (evt.changes.any { ch -> ch.pressed })
                        if (dy > dismissThreshold) onDismiss()
                        dragOffsetY = 0f
                    }
                },
            color = Color.Black.copy(alpha = bgAlpha)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Horizontal ViewPager for Left/Right Swiping
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = items[page]

                    // Page-local gesture & video states
                    var scale by remember { mutableStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }

                    var isVideoPlaying by remember { mutableStateOf(false) }
                    var videoDuration by remember { mutableStateOf(1L) }
                    var videoPosition by remember { mutableStateOf(0L) }
                    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

                    LaunchedEffect(isVideoPlaying) {
                        while (isVideoPlaying) {
                            videoViewRef?.let {
                                videoPosition = it.currentPosition.toLong()
                            }
                            kotlinx.coroutines.delay(200)
                        }
                    }

                    // Reset page states and pause playback on swipe
                    LaunchedEffect(pagerState.currentPage) {
                        if (pagerState.currentPage != page) {
                            scale = 1f
                            offset = Offset.Zero
                            videoViewRef?.let {
                                if (it.isPlaying) {
                                    it.pause()
                                }
                            }
                            isVideoPlaying = false
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (item.isVideo) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .align(Alignment.Center)
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y
                                    )
                                    .pointerInput(scale) {
                                        detectZoomPanGestures(scaleState = { scale }) { pan, zoom ->
                                            scale = (scale * zoom).coerceIn(1f, 5f)
                                            if (scale > 1f) {
                                                offset += pan * scale
                                                val maxBoundX = (scale - 1f) * 450f
                                                val maxBoundY = (scale - 1f) * 700f
                                                offset = Offset(
                                                    offset.x.coerceIn(-maxBoundX, maxBoundX),
                                                    offset.y.coerceIn(-maxBoundY, maxBoundY)
                                                )
                                            } else {
                                                offset = Offset.Zero
                                            }
                                        }
                                    }
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        val authHeader = networkClient.getAuthorizationHeader()
                                        VideoView(ctx).apply {
                                            if (item.contentUri.startsWith("cloud://")) {
                                                val streamUrl = "${networkClient.getServerUrl()}api/files/${item.cloudFileId}/stream"
                                                if (authHeader != null) {
                                                    setVideoURI(Uri.parse(streamUrl), mapOf("Authorization" to authHeader))
                                                } else {
                                                    setVideoURI(Uri.parse(streamUrl))
                                                }
                                            } else {
                                                setVideoURI(Uri.parse(item.contentUri))
                                            }
                                            setOnPreparedListener { mediaPlayer ->
                                                mediaPlayer.isLooping = true
                                                videoDuration = mediaPlayer.duration.toLong()
                                                videoViewRef = this
                                                if (pagerState.currentPage == page) {
                                                    start()
                                                    isVideoPlaying = true
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Invisible clickable overlay for tap controls using combinedClickable
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .combinedClickable(
                                            onClick = { systemUiVisible = !systemUiVisible },
                                            onDoubleClick = {
                                                if (scale > 1f) {
                                                    scale = 1f
                                                    offset = Offset.Zero
                                                } else {
                                                    scale = 2.5f
                                                }
                                            }
                                        )
                                )
                                // Play/Pause Overlay Button
                                AnimatedVisibility(
                                    visible = systemUiVisible,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.align(Alignment.Center)
                                ) {
                                    IconButton(
                                        onClick = {
                                            videoViewRef?.let {
                                                if (it.isPlaying) {
                                                    it.pause()
                                                    isVideoPlaying = false
                                                } else {
                                                    it.start()
                                                    isVideoPlaying = true
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(64.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = if (isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                // Seek scrubber bar
                                AnimatedVisibility(
                                    visible = systemUiVisible,
                                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 120.dp)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatDuration(videoPosition),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Slider(
                                            value = videoPosition.toFloat(),
                                            onValueChange = { pos ->
                                                videoViewRef?.seekTo(pos.toInt())
                                                videoPosition = pos.toLong()
                                            },
                                            valueRange = 0f..max(1f, videoDuration.toFloat()),
                                            colors = SliderDefaults.colors(
                                                thumbColor = NeonAccent,
                                                activeTrackColor = NeonAccent,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                                            ),
                                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                        )
                                        Text(
                                            text = formatDuration(videoDuration),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        } else {
                            var currentImageModel by remember(item.id) {
                                mutableStateOf<Any?>(null)
                            }
                            var currentHeaders by remember(item.id) {
                                mutableStateOf<Map<String, String>?>(null)
                            }

                            // Progressive pipeline:
                            // 1. Immediately load the thumbnail version
                            LaunchedEffect(item.id) {
                                val (thumbData, thumbHdrs) = getMediaUrlSource(item, "thumbnail", networkClient)
                                currentImageModel = thumbData
                                currentHeaders = thumbHdrs

                                // 2. Then trigger the high-quality preview loading
                                val (previewData, previewHdrs) = getMediaUrlSource(item, "preview", networkClient)
                                currentImageModel = previewData
                                currentHeaders = previewHdrs
                            }

                            // 3. Zoom-based original load pipeline
                            LaunchedEffect(item.id, scale) {
                                if (scale > 1.5f) {
                                    val (origData, origHdrs) = getMediaUrlSource(item, "original", networkClient)
                                    currentImageModel = origData
                                    currentHeaders = origHdrs
                                }
                            }

                            if (currentImageModel != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentImageModel)
                                        .apply {
                                            currentHeaders?.forEach { (k, v) -> addHeader(k, v) }
                                        }
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .align(Alignment.Center)
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y
                                        )
                                        .pointerInput(scale) {
                                            detectZoomPanGestures(scaleState = { scale }) { pan, zoom ->
                                                scale = (scale * zoom).coerceIn(1f, 5f)
                                                if (scale > 1f) {
                                                    offset += pan * scale
                                                    val maxBoundX = (scale - 1f) * 450f
                                                    val maxBoundY = (scale - 1f) * 700f
                                                    offset = Offset(
                                                        offset.x.coerceIn(-maxBoundX, maxBoundX),
                                                        offset.y.coerceIn(-maxBoundY, maxBoundY)
                                                    )
                                                } else {
                                                    offset = Offset.Zero
                                                }
                                            }
                                        }
                                        .combinedClickable(
                                            onClick = { systemUiVisible = !systemUiVisible },
                                            onDoubleClick = {
                                                if (scale > 1f) {
                                                    scale = 1f
                                                    offset = Offset.Zero
                                                } else {
                                                    scale = 2.5f
                                                }
                                            }
                                        )
                                )
                            }
                        }
                    }
                }

                // Top Toolbar (Close Button & Title)
                AnimatedVisibility(
                    visible = systemUiVisible,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = activeItem.displayName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (activeItem.contentUri.startsWith("cloud://")) {
                            val coroutineScope = rememberCoroutineScope()
                            val localContext = LocalContext.current
                            IconButton(
                                onClick = {
                                    downloadAndSaveOriginal(localContext, activeItem, networkClient, coroutineScope)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download Original",
                                    tint = NeonAccent
                                )
                            }
                        }
                    }
                }

                // Bottom Panel (Tidy & Clean actions)
                AnimatedVisibility(
                    visible = systemUiVisible,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                )
                            )
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                    ) {
                        // 1. Tidy File Info Summary Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${formatFileSize(activeItem.size)} · ${if (activeItem.isVideo) "Video" else "Ảnh"}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            
                            // Status badge (Cloud backup status)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .background(
                                        if (activeItem.backupStatus == BackupStatus.BACKED_UP) BackupGreen.copy(alpha = 0.15f)
                                        else Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (activeItem.backupStatus == BackupStatus.BACKED_UP) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = if (activeItem.backupStatus == BackupStatus.BACKED_UP) BackupGreen else Color.LightGray,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (activeItem.backupStatus == BackupStatus.BACKED_UP) "Đã sao lưu" else "Chưa sao lưu",
                                    color = if (activeItem.backupStatus == BackupStatus.BACKED_UP) BackupGreen else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        // 2. Clean Row of Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Backup Action (Only show if not backed up yet)
                            if (activeItem.backupStatus != BackupStatus.BACKED_UP) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { onUpload(activeItem) }
                                        .padding(8.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, null, tint = NeonAccent, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Sao lưu", color = NeonAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            
                            // Info Action
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable { showInfo = true }
                                    .padding(8.dp)
                            ) {
                                Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Chi tiết", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            
                            // Delete Action
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable { onDelete(activeItem) }
                                    .padding(8.dp)
                            ) {
                                Icon(Icons.Default.Delete, null, tint = BackupFailed, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Xóa", color = BackupFailed, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Custom details info sheet overlay (renders perfectly in front of photo content)
                AnimatedVisibility(
                    visible = showInfo,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Thông tin chi tiết", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                                IconButton(onClick = { showInfo = false }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = TextSecondary.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(12.dp))

                            InfoRow(icon = Icons.Default.Description, label = "Tên", value = activeItem.displayName)
                            InfoRow(icon = Icons.Default.AspectRatio, label = "Độ phân giải", value = if (activeItem.width > 0 && activeItem.height > 0) "${activeItem.width} × ${activeItem.height}" else "Không rõ")
                            InfoRow(icon = Icons.Default.Storage, label = "Kích thước", value = formatFileSize(activeItem.size))
                            InfoRow(
                                icon = Icons.Default.CalendarToday,
                                label = "Ngày sửa đổi",
                                value = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(activeItem.dateModified * 1000))
                            )
                            InfoRow(icon = Icons.Default.Folder, label = "Thư mục", value = activeItem.relativePath)
                            InfoRow(icon = Icons.Default.Category, label = "Loại tệp", value = activeItem.mimeType)
                        }
                    }
                }
            }
        }
    }
}

// Utilities for Flow collection in Compose
@Composable
fun <T> StateFlow<T>.collectFlow(): State<T> {
    return collectAsState()
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.2f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDuration(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    val hr = (ms / (1000 * 60 * 60)) % 24
    return if (hr > 0) {
        String.format("%d:%02d:%02d", hr, min, sec)
    } else {
        String.format("%d:%02d", min, sec)
    }
}

suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectZoomPanGestures(
    scaleState: () -> Float,
    onGesture: (pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val pointerCount = event.changes.size
                val scale = scaleState()
                if (pointerCount >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    event.changes.forEach { it.consume() }
                    onGesture(pan, zoom)
                } else if (pointerCount == 1 && scale > 1f) {
                    val pan = event.calculatePan()
                    event.changes.forEach { it.consume() }
                    onGesture(pan, 1f)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

fun getMediaUrlSource(item: MediaItem, type: String, networkClient: NetworkClient): Pair<Any, Map<String, String>?> {
    val authHeader = networkClient.getAuthorizationHeader()
    return if (item.contentUri.startsWith("cloud://")) {
        val baseUrl = networkClient.getServerUrl()
        val url = when (type) {
            "thumbnail" -> "${baseUrl}api/media/${item.cloudFileId}/thumbnail"
            "preview" -> "${baseUrl}api/media/${item.cloudFileId}/preview"
            else -> "${baseUrl}api/files/${item.cloudFileId}/stream"
        }
        val headers = if (authHeader != null) mapOf<String, String>("Authorization" to authHeader) else null
        url to (headers as Map<String, String>?)
    } else {
        item.contentUri to (null as Map<String, String>?)
    }
}

fun getMediaSource(item: MediaItem, networkClient: NetworkClient): Pair<Any, Map<String, String>?> {
    return getMediaUrlSource(item, "original", networkClient)
}

fun downloadAndSaveOriginal(
    context: android.content.Context,
    item: MediaItem,
    networkClient: NetworkClient,
    scope: kotlinx.coroutines.CoroutineScope
) {
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val streamUrl = "${networkClient.getServerUrl()}api/files/${item.cloudFileId}/stream"
            val authHeader = networkClient.getAuthorizationHeader()
            
            val request = okhttp3.Request.Builder()
                .url(streamUrl)
                .apply {
                    if (authHeader != null) {
                        addHeader("Authorization", authHeader)
                    }
                }
                .build()
                
            val client = networkClient.okHttpClient
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw java.io.IOException("Server error code: ${response.code}")
            }
            
            val body = response.body ?: throw java.io.IOException("Empty body")
            val isVideo = item.mimeType.startsWith("video/")
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            
            val collectionUri = if (isVideo) {
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            
            val uri = context.contentResolver.insert(collectionUri, contentValues) ?: throw java.io.IOException("Failed to create MediaStore entry")
            
            val os = context.contentResolver.openOutputStream(uri)
            if (os != null) {
                os.use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            } else {
                throw java.io.IOException("Failed to open output stream")
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Tải thành công file ${item.displayName} về bộ sưu tập!", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Tải file thất bại: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
