package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.AuthViewModel
import com.example.AuthViewModelFactory
import com.example.ui.AuthScreen

class MainActivity : ComponentActivity() {

    // Global callback for file uploads in WebView
    var uploadMessageCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen(
                    onSetUploadCallback = { callback ->
                        uploadMessageCallback = callback
                    },
                    getUploadCallback = { uploadMessageCallback }
                )
            }
        }
    }
}

/**
 * JavaScript Interface to communicate blob file downloads back to Kotlin.
 */
class WebAppInterface(
    private val context: Context,
    private val onDownloadBase64: (base64Str: String, contentDisposition: String, mimeType: String) -> Unit,
    private val onLogoutTriggered: () -> Unit = {}
) {
    @JavascriptInterface
    fun downloadBase64(base64Data: String, contentDisposition: String, mimetype: String) {
        onDownloadBase64(base64Data, contentDisposition, mimetype)
    }

    @JavascriptInterface
    fun onNativeLogout() {
        onLogoutTriggered()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MainAppScreen(
    onSetUploadCallback: (ValueCallback<Array<Uri>>?) -> Unit,
    getUploadCallback: () -> ValueCallback<Array<Uri>>?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Room database synchronization and unified ViewModel setup
    val database = remember(context) { com.example.data.AppDatabase.getDatabase(context) }
    val userRepository = remember(database) { com.example.data.UserRepository(database.userDao()) }
    val authViewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = com.example.AuthViewModelFactory(userRepository)
    )
    val currentAuthUser by authViewModel.userState.collectAsState()

    // 1. Connection states
    fun isConnectedNow(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    var isNetworkConnected by remember { mutableStateOf(isConnectedNow(context)) }

    // Dynamic internet status monitoring
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkConnected = true
            }
            override fun onLost(network: Network) {
                isNetworkConnected = false
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // Fallback if registry fails
        }
        onDispose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // 2. Navigation & Loading State variables
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageLoadingProgress by remember { mutableIntStateOf(0) }
    var isPageCurrentlyLoading by remember { mutableStateOf(true) }
    var currentWebUrl by remember { mutableStateOf("file:///android_asset/index.html") }
    var isSplashVisible by remember { mutableStateOf(true) }
    var showToolkitDialog by remember { mutableStateOf(false) }
    var showBentoDashboard by remember { mutableStateOf(true) }
    var currentTab by remember { mutableStateOf("home") }

    // Backup splash auto-fade after 4 seconds to protect users against laggy networks
    LaunchedEffect(Unit) {
        delay(4000)
        isSplashVisible = false
    }

    // Reactive bidirectional synchronization of the active tab state between native and WebView
    LaunchedEffect(currentTab, isPageCurrentlyLoading, webViewInstance) {
        if (!isPageCurrentlyLoading && webViewInstance != null) {
            webViewInstance?.evaluateJavascript("if (window.setReactTab) { window.setReactTab('$currentTab'); }", null)
            if (currentTab == "home") {
                webViewInstance?.evaluateJavascript("if (window.clearActiveTool) { window.clearActiveTool(); }", null)
            }
        }
    }

    // Reactive synchronization of authenticated User identity with WebView profile context
    LaunchedEffect(currentAuthUser, isPageCurrentlyLoading, webViewInstance) {
        if (!isPageCurrentlyLoading && webViewInstance != null) {
            val user = currentAuthUser
            if (user != null) {
                val escapedName = user.name.replace("'", "\\'")
                val escapedEmail = user.email.replace("'", "\\'")
                val escapedAvatar = user.avatar.replace("'", "\\'")
                val jsExpr = "if (window.setReactUser) { window.setReactUser({ name: '$escapedName', email: '$escapedEmail', plan: 'private-pro', joined: '${user.joinedDate}', avatar: '$escapedAvatar' }); }"
                webViewInstance?.evaluateJavascript(jsExpr, null)
            } else {
                webViewInstance?.evaluateJavascript("if (window.setReactUser) { window.setReactUser(null); }", null)
            }
        }
    }

    // WebView File Chooser Launcher Setup
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val currentCallback = getUploadCallback()
        if (currentCallback != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val parsedResults = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                currentCallback.onReceiveValue(parsedResults)
            } else {
                currentCallback.onReceiveValue(null)
            }
            onSetUploadCallback(null)
        }
    }

    // Helper to safely issue downloads (supports: Normal URLs, Base64 Data URLs, Blobs)
    val handleDownloadAction = remember(context, webViewInstance) {
        { url: String, userAgent: String, contentDisposition: String, mimetype: String ->
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (url.startsWith("data:")) {
                    // Base64 Local Data Attachment
                    try {
                        val parts = url.split(",")
                        if (parts.size > 1) {
                            val base64Content = parts[1]
                            val decodedBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
                            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype) ?: "royal_pdf_render.pdf"
                            
                            var savedUri: Uri? = null
                            var savedMessage = ""
                            
                            // Strategy 1: Save using MediaStore for public Downloads folder (Android Q+)
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    val contentValues = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, guessedName)
                                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimetype)
                                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                    }
                                    val resolver = context.contentResolver
                                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                    if (uri != null) {
                                        resolver.openOutputStream(uri)?.use { outputStream ->
                                            outputStream.write(decodedBytes)
                                        }
                                        savedUri = uri
                                        savedMessage = "Saved to Downloads: $guessedName"
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            
                            // Strategy 2: Direct file write fallback for older versions
                            if (savedUri == null) {
                                try {
                                    val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                    if (!publicDownloadsDir.exists()) {
                                        publicDownloadsDir.mkdirs()
                                    }
                                    val saveFile = java.io.File(publicDownloadsDir, guessedName)
                                    java.io.FileOutputStream(saveFile).use { fos ->
                                        fos.write(decodedBytes)
                                    }
                                    savedUri = Uri.fromFile(saveFile)
                                    savedMessage = "Saved to Downloads: $guessedName"
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            
                            // Strategy 3: Sandbox/App-private safe folder fallback if public is completely blocked
                            if (savedUri == null) {
                                try {
                                    val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                                    if (!fallbackDir.exists()) {
                                        fallbackDir.mkdirs()
                                    }
                                    val fallbackFile = java.io.File(fallbackDir, guessedName)
                                    java.io.FileOutputStream(fallbackFile).use { fos ->
                                        fos.write(decodedBytes)
                                    }
                                    savedUri = Uri.fromFile(fallbackFile)
                                    savedMessage = "Saved to safe space: $guessedName"
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (savedUri != null) {
                                    Toast.makeText(context, savedMessage, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Permission error: Cannot save file", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "Invalid data URL format", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Saved offline failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (url.startsWith("blob:")) {
                    // Blob conversion bridge utilizing JavaScript fetch -> bridge back!
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Converting secure PDF Blob...", Toast.LENGTH_SHORT).show()
                        webViewInstance?.evaluateJavascript(
                            """
                            (function() {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', '$url', true);
                                xhr.responseType = 'blob';
                                xhr.onload = function(e) {
                                    if (this.status == 200) {
                                        var blob = this.response;
                                        var reader = new FileReader();
                                        reader.readAsDataURL(blob);
                                        reader.onloadend = function() {
                                            var base64data = reader.result;
                                            AndroidAppBridge.downloadBase64(base64data, '$contentDisposition', '$mimetype');
                                        }
                                    }
                                };
                                xhr.send();
                            })();
                            """.trimIndent(), null
                        )
                    }
                } else {
                    // Standard server hosted file downloads via OS DownloadManager
                    try {
                        val request = DownloadManager.Request(Uri.parse(url)).apply {
                            setMimeType(mimetype)
                            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype) ?: "Royal_PDF_toolkit.pdf"
                            
                            // Pass authorization cookies to download manager cleanly
                            val cookies = CookieManager.getInstance().getCookie(url)
                            addRequestHeader("cookie", cookies)
                            addRequestHeader("User-Agent", userAgent)
                            
                            setDescription("Downloading Royal PDF customized file...")
                            setTitle(guessedName)
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Secure Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    // Handles native hardware/gesture back key to go back in WebView history instead of closing app
    val canGoBackState = remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBackState.value || !showBentoDashboard) {
        if (!showBentoDashboard) {
            showBentoDashboard = true
        } else if (canGoBackState.value) {
            webViewInstance?.goBack()
        }
    }

    // Capture latest reference of download logic to prevent stale closure capture bug
    val latestDownloadHandler by rememberUpdatedState(handleDownloadAction)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                // Modern Material 3 TopBar with Certificate SSL Indicator & Brand Title
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "SSL Secure Badge",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Royal PDF Tools",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Royal PDF Tools - Ultimate PDF toolkit",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showBentoDashboard = !showBentoDashboard },
                            modifier = Modifier.testTag("app_btn_toggle_dashboard")
                        ) {
                            Icon(
                                imageVector = if (showBentoDashboard) Icons.Default.Star else Icons.Default.Home,
                                contentDescription = "Toggle Bento Dashboard",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { showToolkitDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "App Toolkit Information",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )

                // Indeterminate or loading progress indicator for top bar
                AnimatedVisibility(
                    visible = isPageCurrentlyLoading,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    LinearProgressIndicator(
                        progress = { pageLoadingProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.background
                    )
                }

                // Elegant Material 3 offline status banner
                AnimatedVisibility(
                    visible = !isNetworkConnected,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Network Offline Warning Indicator",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Offline Mode: Connection required to load PDF-Lib Workspace",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .shadow(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                val activeTabId = when {
                    showBentoDashboard -> "home"
                    else -> currentTab
                }

                // Left item: History of processed PDFs
                NavigationBarItem(
                    selected = activeTabId == "history",
                    onClick = {
                        currentTab = "history"
                        showBentoDashboard = false
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "User Processing History"
                        )
                    },
                    label = {
                        Text(
                            text = "History",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    modifier = Modifier.testTag("app_btn_history")
                )

                // Middle item: Home button
                NavigationBarItem(
                    selected = activeTabId == "home",
                    onClick = {
                        showBentoDashboard = true
                        currentTab = "home"
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Return Home"
                        )
                    },
                    label = {
                        Text(
                            text = "Home",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    modifier = Modifier.testTag("app_btn_home")
                )

                // Right item: User Profile
                NavigationBarItem(
                    selected = activeTabId == "profile",
                    onClick = {
                        currentTab = "profile"
                        showBentoDashboard = false
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User Profile"
                        )
                    },
                    label = {
                        Text(
                            text = "Profile",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    modifier = Modifier.testTag("app_btn_profile")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main Content: WebView Container (Mounted online)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewInstance = this
                        
                        // Custom system interface settings
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            allowFileAccess = true
                            allowContentAccess = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = WebSettings.LOAD_DEFAULT
                            
                            // Ensure modern web compatibility
                            userAgentString = userAgentString.replace("; wv)", ")")
                        }

                        // WebApp download callbacks mapping
                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            latestDownloadHandler(url, userAgent, contentDisposition, mimetype)
                        }

                        // JS Native Bridge to extract blob downloads
                        addJavascriptInterface(
                            WebAppInterface(
                                context = context,
                                onDownloadBase64 = { base64Data, disposition, mimetype ->
                                    coroutineScope.launch {
                                        latestDownloadHandler(base64Data, "", disposition, mimetype)
                                    }
                                },
                                onLogoutTriggered = {
                                    coroutineScope.launch {
                                        authViewModel.logout()
                                    }
                                }
                            ),
                            "AndroidAppBridge"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isPageCurrentlyLoading = true
                                url?.let { currentWebUrl = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isPageCurrentlyLoading = false
                                isSplashVisible = false
                                canGoBackState.value = view?.canGoBack() ?: false
                                url?.let { currentWebUrl = it }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                // Fallback handler if server returns bad states
                                if (failingUrl?.startsWith("https://swagchup.in") == true) {
                                    isPageCurrentlyLoading = false
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageLoadingProgress = newProgress
                            }

                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                consoleMessage?.let {
                                    android.util.Log.d("WebViewConsole", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                                }
                                return true
                            }

                            override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                            ): Boolean {
                                Toast.makeText(context, message ?: "", Toast.LENGTH_SHORT).show()
                                result?.confirm()
                                return true
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                // Safely handle document inputs for merging/splitting custom PDF uploads
                                val previousCallback = getUploadCallback()
                                previousCallback?.onReceiveValue(null)
                                onSetUploadCallback(filePathCallback)

                                if (fileChooserParams != null) {
                                    try {
                                        val intent = fileChooserParams.createIntent()
                                        fileChooserLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        onSetUploadCallback(null)
                                        Toast.makeText(context, "Browser picker unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
                                        return false
                                    }
                                }
                                return true
                            }
                        }

                        // Load Initial URI
                        loadUrl(currentWebUrl)
                    }
                },
                update = { webView ->
                    // Network switch recovery logic: when network gets connected back, reload webview if it was blank
                    if (isNetworkConnected && webView.url == null) {
                        webView.loadUrl("file:///android_asset/index.html")
                    }
                }
            )

            // Bento Grid UI Overlay Dashboard (Renders on top of WebView, under offline state and splash)
            AnimatedVisibility(
                visible = showBentoDashboard,
                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    BentoDashboard(
                        onSelectToolUrl = { url ->
                            if (url.startsWith("file://") || isNetworkConnected) {
                                webViewInstance?.loadUrl(url)
                                showBentoDashboard = false
                            } else {
                                Toast.makeText(context, "Cannot load remote site offline. Check connection.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // Native Material 3 Auth Screen (Renders when active tab is profile)
            AnimatedVisibility(
                visible = currentTab == "profile" && !showBentoDashboard,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(400))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AuthScreen(authViewModel = authViewModel)
                }
            }

            // Offline overlay: Shows if user drops offline suddenly or starts offline for remote pages
            AnimatedVisibility(
                visible = !isNetworkConnected && currentWebUrl.startsWith("http"),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Connection Lost Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Connection Offline",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Royal PDF Tools runs privacy-protected PDF conversions. Please restore your internet connection to continue updating documents.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            isNetworkConnected = isConnectedNow(context)
                            if (isNetworkConnected) {
                                webViewInstance?.reload()
                            } else {
                                Toast.makeText(context, "Still Offline. Check WiFi/Data.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(50.dp)
                            .testTag("app_btn_retry")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry Connection")
                            Text("Reconnect Tool", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // High Fidelity Animated Splash Screen (Fades out when load completes)
            AnimatedVisibility(
                visible = isSplashVisible,
                exit = fadeOut(tween(durationMillis = 600))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        // High resolution icon rendering
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .shadow(8.dp, RoundedCornerShape(28.dp))
                                .clip(RoundedCornerShape(28.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            // Centered PNG asset of our stylized logo we generated
                            val rawLogoPainter = painterResource(id = R.drawable.app_logo_foreground_1779362816411)
                            androidx.compose.foundation.Image(
                                painter = rawLogoPainter,
                                contentDescription = "Royal PDF Tools App Logo",
                                modifier = Modifier.size(100.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "Royal PDF Tools",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Ultimate XML & PDF Toolkit",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(48.dp))

                        // Stylish modern pulse circle indicator
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Initializing secure workspace...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            fontWeight = FontWeight.Light
                        )
                    }
                }
            }

            // About Dialog Info popup
            if (showToolkitDialog) {
                AlertDialog(
                    onDismissRequest = { showToolkitDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Toolkit Seal Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    title = {
                        Text(
                            "Royal PDF Tools",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Text(
                                "Royal PDF Tools wraps 35+ premium converters, compressors, editors, and security managers safely into your android device.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                lineHeight = 20.sp
                            )
                            HorizontalDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                                Text(
                                    "Processed locally: Complete Privacy",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2196F3))
                                )
                                Text(
                                    "Includes Merge, Split, Convert & Flatten",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { showToolkitDialog = false },
                            modifier = Modifier.testTag("dialog_btn_close")
                        ) {
                            Text("Awesome", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

@Composable
fun BentoDashboard(
    onSelectToolUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val goldBg = if (isDark) Color(0xFF625B3E) else Color(0xFFFFF8E1)
    val goldOnBg = if (isDark) Color(0xFFFFECB3) else Color(0xFF5D4037)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- 1. Featured Card: Large (Col-span 4, Row-span 2) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onSelectToolUrl("file:///android_asset/index.html") }
                .padding(20.dp)
        ) {
            // Overlapping decorative background blob mimicking the original HTML blur circle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 15.dp, y = 15.dp)
                    .size(110.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "PREMIUM WORKSPACE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Royal PDF\nToolkit",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 32.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { onSelectToolUrl("file:///android_asset/index.html") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Launch Workspace",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // --- 2. Middle Row: Left Tall Card & (Right Top + Right Bottom) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2.2f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // LEFT COLUMN (Tall Card: custom Merge & Split)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                    .clickable { onSelectToolUrl("file:///android_asset/index.html?tool=merge") }
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Combine & Merge\nPDF Documents",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp
                    )

                    // Simulated visual illustration matching Bento theme
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.2f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Merge papers icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Merge Files",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // RIGHT COLUMN (Top Card & Bottom Card)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Right Top: Small (Compress)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                        .clickable { onSelectToolUrl("file:///android_asset/index.html?tool=compress") }
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Compress Icon",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Optimize &\nCompress PDF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 14.sp
                        )
                    }
                }

                // Right Bottom: Small (Secure Accessories)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                        .clickable { onSelectToolUrl("file:///android_asset/index.html?tool=unlock") }
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Secure Lock Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Protect &\nUnlock PDFs",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // --- 3. Bottom Featured Card: Wide (Col-span 4, Row-span 1) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(goldBg)
                .clickable { onSelectToolUrl("file:///android_asset/index.html") }
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text(
                        text = "Complete Local Sovereignty",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = goldOnBg
                    )
                    Text(
                        text = "All files remain processed in-device for total privacy",
                        fontSize = 11.sp,
                        color = goldOnBg.copy(alpha = 0.75f)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(goldOnBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Forward Action Arrow",
                        tint = goldBg,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
