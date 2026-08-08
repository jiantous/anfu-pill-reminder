package com.jian.pillreminder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jian.pillreminder.notify.ReminderHealth
import com.jian.pillreminder.notify.Reminders
import com.jian.pillreminder.ui.MedViewModel
import com.jian.pillreminder.data.BackupFile
import com.jian.pillreminder.data.BackupManager
import com.jian.pillreminder.data.BackupSummary
import androidx.core.content.FileProvider
import com.jian.pillreminder.ui.screens.BackupReminderBanner
import com.jian.pillreminder.ui.screens.BackupScreen
import com.jian.pillreminder.ui.screens.EditMedicationScreen
import com.jian.pillreminder.ui.screens.ImportConfirmDialog
import com.jian.pillreminder.ui.screens.HistoryScreen
import com.jian.pillreminder.ui.screens.MedicationsScreen
import com.jian.pillreminder.ui.screens.ReminderHealthBanner
import com.jian.pillreminder.ui.screens.ReminderSetupScreen
import com.jian.pillreminder.ui.screens.ScanLeafletScreen
import com.jian.pillreminder.ui.screens.TodayScreen
import com.jian.pillreminder.ui.screens.AboutScreen
import com.jian.pillreminder.ui.screens.FEEDBACK_EMAIL
import com.jian.pillreminder.ui.screens.PROJECT_URL
import com.jian.pillreminder.ui.screens.RELEASES_URL
import com.jian.pillreminder.ui.screens.SettingsScreen
import com.jian.pillreminder.ui.components.suggestIconForUnit
import com.jian.pillreminder.ui.theme.PillReminderTheme

class MainActivity : ComponentActivity() {

    /** 每次从桌面图标/通知重新进入时自增，用来把导航栈重置回今日清单。 */
    private val relaunchSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 不恢复上次的导航栈：吃药提醒每次打开都应先看到"今天要吃什么"，
        // 而不是停在上次离开时的编辑页。传 null 让 Compose Navigation 从起始页开始。
        super.onCreate(null)
        enableEdgeToEdge()
        Reminders.ensureChannels(this)
        setContent {
            PillReminderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PillApp(relaunchSignal.intValue)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 用户重新打开 App（而不是在应用内部导航）时，回到今日清单，
        // 不要停在上次离开时的编辑页——吃药提醒打开就该先看到"今天要吃什么"。
        relaunchSignal.intValue++
    }
}

private sealed class Dest(val route: String, val label: String) {
    data object Today : Dest("today", "今天")
    data object Meds : Dest("meds", "我的药箱")
    data object History : Dest("history", "统计")
    data object Edit : Dest("edit", "编辑")
    data object Scan : Dest("scan", "拍说明书")
    data object Setup : Dest("setup", "提醒设置")
    data object Backup : Dest("backup", "备份")

    // 注意 route 不能以已有的 route 为前缀：下面 isEditing 用的是 startsWith。
    // "settings" 不是 "setup" 的前缀，安全。
    data object Settings : Dest("settings", "设置")
    data object About : Dest("about", "关于")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PillApp(relaunchSignal: Int = 0) {
    val vm: MedViewModel = viewModel()
    // 用 remember 而非 rememberSaveable 语义的 rememberNavController：
    // 后者会从 SavedStateRegistry 恢复上次的 back stack，导致重开 App 停在编辑页。
    // 吃药提醒每次打开都应落在今日清单，所以这里每个进程都新建一个干净的控制器。
    val context0 = LocalContext.current
    val nav = remember {
        androidx.navigation.NavHostController(context0).apply {
            navigatorProvider.addNavigator(androidx.navigation.compose.ComposeNavigator())
            navigatorProvider.addNavigator(androidx.navigation.compose.DialogNavigator())
        }
    }
    // OCR 识别结果的交接站：扫描页写入，编辑页读取后预填表单
    var scanResult by remember { mutableStateOf<com.jian.pillreminder.domain.LeafletParser.Result?>(null) }
    // 正在编辑的药品 id（null = 新建）。放在 state 里而不是路由参数里，
    // 避免带参路由被 Navigation 恢复导致重开 App 直接落在编辑页。
    var editingId by remember { mutableStateOf<String?>(null) }
    // 顶栏 ⋮ 菜单的展开状态
    var menuOpen by remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val context = LocalContext.current

    // 首次进入：把闹钟排上（示例数据改为用户在空状态里主动选择添加）
    LaunchedEffect(Unit) {
        vm.rescheduleAllAlarms()
    }

    // 从桌面图标/通知重新进入时（onNewIntent）回到今日清单
    LaunchedEffect(relaunchSignal) {
        if (relaunchSignal > 0) {
            scanResult = null
            nav.navigate(Dest.Today.route) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // 每次回到前台刷新"是否错过"的判定
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- 备份 ----
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember {
        mutableStateOf<Pair<BackupFile, BackupSummary>?>(null)
    }

    /**
     * 备份：弹系统的"新建文件"选择器，文件名已经填好，用户只要选个位置按保存。
     *
     * 用 CreateDocument 而不是 OpenDocumentTree，是因为后者要用户先"授权一个文件夹"、
     * 多一道权限弹窗，还得让 App 把这个文件夹记下来——多出一个用户得先理解的概念。
     * CreateDocument 一个弹窗走完，且系统自己会记住上次去过哪，下次想换位置直接在
     * 选择器里翻到别处就行。
     */
    val backupFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupBusy = true
            val result = BackupManager.writeToFile(context, uri, vm.buildBackupContent())
            backupBusy = false
            backupMessage = if (result.isSuccess) {
                vm.markBackedUp()
                "备份已保存。"
            } else {
                "保存失败：" + (result.exceptionOrNull()?.message ?: "未知原因")
            }
        }
    }

    // ---- CSV 导出 ----
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    // 「另存为」是异步的：点按钮时先把内容算好存这里，等用户选完位置再写
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    val createCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val content = pendingCsv
        pendingCsv = null
        if (uri != null && content != null) {
            val r = BackupManager.writeToFile(context, uri, content)
            settingsMessage = if (r.isSuccess) "已导出。用 Excel 或表格类应用都能打开。"
            else "导出失败：" + (r.exceptionOrNull()?.message ?: "未知原因")
        }
    }

    // 导入：读取用户选的备份文件，先给摘要让用户确认
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            BackupManager.readBackup(context, uri)
                .onSuccess { backup -> pendingImport = backup to BackupManager.summarize(backup) }
                .onFailure { e ->
                    backupMessage = "读不了这个文件：" + (e.message ?: "格式不对") +
                        "\n请确认选的是安服导出的备份文件。"
                }
        }
    }

    // 提醒可靠性体检：通知 / 精确闹钟 / 电池优化豁免
    var healthChecks by remember { mutableStateOf(ReminderHealth.checks(context)) }
    fun refreshHealth() { healthChecks = ReminderHealth.checks(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshHealth()
        if (granted) vm.rescheduleAllAlarms()
    }

    fun fix(check: ReminderHealth.Check) {
        when (val a = check.action) {
            is ReminderHealth.Action.RequestNotificationPermission ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ReminderHealth.launchFirstAvailable(context, listOf(ReminderHealth.appDetailsSettings(context)))
                }
            is ReminderHealth.Action.OpenSettings ->
                ReminderHealth.launchFirstAvailable(context, a.intents)
        }
    }

    // 每次回到前台都重新体检（用户可能刚在系统设置里改过）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshHealth()
                vm.rescheduleAllAlarms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首次启动且还有未就绪项时，直接进引导页
    val appData by vm.data.collectAsState()
    var guideChecked by remember { mutableStateOf(false) }
    LaunchedEffect(appData.setupGuideShown, healthChecks) {
        if (guideChecked) return@LaunchedEffect
        guideChecked = true
        if (!appData.setupGuideShown && healthChecks.any { !it.granted }) {
            nav.navigate(Dest.Setup.route)
        }
    }

    // 这些是自带顶栏的全屏页面，要把外层顶栏/底栏/悬浮按钮藏掉，
    // 否则会出现两条标题栏
    val isEditing = route?.startsWith(Dest.Edit.route) == true ||
        route?.startsWith(Dest.Scan.route) == true ||
        route?.startsWith(Dest.Setup.route) == true ||
        route?.startsWith(Dest.Backup.route) == true ||
        route?.startsWith(Dest.Settings.route) == true ||
        route?.startsWith(Dest.About.route) == true

    Scaffold(
        topBar = {
            if (!isEditing) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (route) {
                                Dest.Meds.route -> "我的药箱"
                                Dest.History.route -> "服药统计"
                                else -> "安服"
                            }
                        )
                    },
                    actions = {
                        // 一个 ⋮ 收纳全部次要入口，顶栏不再堆图标
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("设置") },
                                    leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(Dest.Settings.route)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("备份") },
                                    leadingIcon = { Icon(Icons.Filled.CloudUpload, null) },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(Dest.Backup.route)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("关于") },
                                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(Dest.About.route)
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (!isEditing) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Dest.Today.route,
                        onClick = { nav.navigate(Dest.Today.route) { launchSingleTop = true } },
                        icon = {
                            Icon(
                                if (route == Dest.Today.route) Icons.Filled.Today
                                else Icons.Outlined.Today,
                                contentDescription = null
                            )
                        },
                        label = { Text(Dest.Today.label) }
                    )
                    NavigationBarItem(
                        selected = route == Dest.Meds.route,
                        onClick = { nav.navigate(Dest.Meds.route) { launchSingleTop = true } },
                        icon = {
                            Icon(
                                if (route == Dest.Meds.route) Icons.Filled.Medication
                                else Icons.Outlined.Medication,
                                contentDescription = null
                            )
                        },
                        label = { Text(Dest.Meds.label) }
                    )
                    NavigationBarItem(
                        selected = route == Dest.History.route,
                        onClick = { nav.navigate(Dest.History.route) { launchSingleTop = true } },
                        icon = {
                            Icon(
                                if (route == Dest.History.route) Icons.Filled.Insights
                                else Icons.Outlined.Insights,
                                contentDescription = null
                            )
                        },
                        label = { Text(Dest.History.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (route == Dest.Today.route || route == Dest.Meds.route) {
                ExtendedFloatingActionButton(
                    onClick = { editingId = null; nav.navigate(Dest.Edit.route) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("加药") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Today.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Today.route) {
                TodayScreen(
                    vm = vm,
                    onAddMedication = { editingId = null; nav.navigate(Dest.Edit.route) },
                    onOpenMedication = { id -> editingId = id; nav.navigate(Dest.Edit.route) },
                    permissionBanner = run {
                        val pending = healthChecks.filterNot { it.granted }
                        val showHealth = pending.isNotEmpty() && !appData.healthBannerDismissed
                        val backupDays = vm.daysSinceBackup()
                        // 超过 30 天没备份（或从未备份且已有药）才提醒，避免刚装就啰嗦
                        val showBackup = !appData.backupReminderDismissed &&
                            appData.medications.isNotEmpty() &&
                            (backupDays == null || backupDays > 30)
                        when {
                            // 提醒能不能响比备份更要紧，优先显示
                            showHealth -> {
                                {
                                    ReminderHealthBanner(
                                        pending = pending,
                                        onOpenSetup = { nav.navigate(Dest.Setup.route) },
                                        onDismiss = { vm.dismissHealthBanner() }
                                    )
                                }
                            }
                            showBackup -> {
                                {
                                    BackupReminderBanner(
                                        days = backupDays,
                                        onOpenBackup = { nav.navigate(Dest.Backup.route) },
                                        onDismiss = { vm.dismissBackupReminder() }
                                    )
                                }
                            }
                            else -> null
                        }
                    }
                )
            }

            composable(Dest.Meds.route) {
                MedicationsScreen(
                    vm = vm,
                    onOpenMedication = { id -> editingId = id; nav.navigate(Dest.Edit.route) }
                )
            }

            composable(Dest.History.route) {
                HistoryScreen(vm = vm)
            }

            composable(Dest.Edit.route) {
                val id = editingId
                val existing = vm.medicationById(id)
                val ocr = scanResult
                // 新建时只生成一次草稿；有 OCR 结果就把识别到的字段预填进去
                val draft = remember(id, ocr) {
                    val base = existing ?: vm.newMedicationDraft()
                    if (existing != null || ocr == null) base else base.copy(
                        name = ocr.name ?: base.name,
                        dosage = ocr.dosage ?: base.dosage,
                        unit = ocr.unit ?: base.unit,
                        // 图标按剂型分类，识别到的单位就是剂型线索（"粒"→胶囊）
                        iconIndex = ocr.unit?.let { suggestIconForUnit(it) } ?: base.iconIndex,
                        times = ocr.suggestedTimes.ifEmpty { base.times },
                        schedule = ocr.schedule ?: base.schedule,
                        mealRelation = ocr.mealRelation ?: base.mealRelation
                    )
                }
                val note = remember(ocr, existing) {
                    // 示例药不排闹钟，得说清楚，否则用户以为提醒坏了
                    if (existing?.isSample == true) {
                        "这是示例药品，不会真的提醒你。改动并保存后它就会变成正式药品，按你设的时间提醒。"
                    } else if (existing != null || ocr == null) null
                    else {
                        val filled = buildList {
                            if (ocr.name != null) add("药名")
                            if (ocr.dosage != null) add("剂量")
                            if (ocr.timesPerDay != null) add("服药时间")
                            if (ocr.schedule != null) add("用药频率")
                            if (ocr.mealRelation != null) add("进餐要求")
                        }
                        if (filled.isEmpty()) null
                        else "已根据说明书填好：${filled.joinToString("、")}。请核对无误后再保存。"
                    }
                }

                EditMedicationScreen(
                    initial = draft,
                    isNew = existing == null,
                    onSave = { med ->
                        vm.saveMedication(med)
                        scanResult = null
                        nav.popBackStack()
                    },
                    onDelete = existing?.let {
                        {
                            vm.deleteMedication(it)
                            nav.popBackStack()
                        }
                    },
                    onBack = { scanResult = null; nav.popBackStack() },
                    onScanLeaflet = if (existing == null) {
                        { nav.navigate(Dest.Scan.route) }
                    } else null,
                    prefillNote = note
                )
            }

            composable(Dest.Setup.route) {
                ReminderSetupScreen(
                    checks = healthChecks,
                    vendorHint = remember { ReminderHealth.vendorHint() },
                    onFix = { fix(it) },
                    onDone = {
                        vm.markSetupGuideShown()
                        vm.rescheduleAllAlarms()
                        nav.popBackStack()
                    },
                    onSkip = {
                        vm.markSetupGuideShown()
                        vm.dismissHealthBanner()
                        nav.popBackStack()
                    }
                )
            }

            composable(Dest.Backup.route) {
                BackupScreen(
                    lastBackupDate = appData.lastBackupDate,
                    daysSinceBackup = vm.daysSinceBackup(),
                    medicationCount = appData.medications.size,
                    logCount = appData.logs.size,
                    busy = backupBusy,
                    message = backupMessage,
                    onBackup = {
                        backupFileLauncher.launch(BackupManager.suggestFileName())
                    },
                    onImport = {
                        openFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    onClearMessage = { backupMessage = null },
                    onBack = { nav.popBackStack() }
                )

                pendingImport?.let { pair ->
                    ImportConfirmDialog(
                        summary = pair.second,
                        currentMedCount = appData.medications.size,
                        currentLogCount = appData.logs.size,
                        onConfirm = { mode ->
                            vm.applyBackup(pair.first, mode)
                            val s2 = pair.second
                            pendingImport = null
                            backupMessage = "已恢复：" + s2.medicationCount + " 种药、" +
                                s2.logCount + " 条记录，提醒也重新排好了。"
                        },
                        onDismiss = { pendingImport = null }
                    )
                }
            }

            composable(Dest.Scan.route) {
                ScanLeafletScreen(
                    onUseResult = { r ->
                        scanResult = r
                        // 回到编辑页（它会读取 scanResult 预填表单）
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() }
                )
            }

            composable(Dest.Settings.route) {
                SettingsScreen(
                    snoozeMinutes = appData.snoozeMinutes,
                    ongoingNotification = appData.ongoingNotification,
                    logCount = appData.logs.size,
                    busy = backupBusy,
                    message = settingsMessage,
                    onSnoozeMinutesChange = { vm.setSnoozeMinutes(it) },
                    onOngoingNotificationChange = { vm.setOngoingNotification(it) },
                    onExportCsv = { days ->
                        pendingCsv = vm.buildCsvContent(days)
                        createCsvLauncher.launch(vm.suggestCsvFileName())
                    },
                    onShareCsv = { days ->
                        // 和分享备份同一条路子：写到缓存目录再交给 FileProvider
                        runCatching {
                            val dir = java.io.File(context.cacheDir, "backup").apply { mkdirs() }
                            val f = java.io.File(dir, vm.suggestCsvFileName())
                            f.writeText(vm.buildCsvContent(days))
                            val shareUri = FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", f
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        putExtra(Intent.EXTRA_SUBJECT, "服药记录")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "分享服药记录"
                                )
                            )
                        }.onFailure {
                            settingsMessage = "分享失败：" + (it.message ?: "未知原因")
                        }
                    },
                    onOpenReminderSetup = { nav.navigate(Dest.Setup.route) },
                    onClearMessage = { settingsMessage = null },
                    onBack = { nav.popBackStack() }
                )
            }

            composable(Dest.About.route) {
                val versionName = remember {
                    runCatching {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: ""
                    }.getOrDefault("")
                }
                AboutScreen(
                    versionName = versionName,
                    onOpenProjectPage = {
                        // 没有浏览器的极端情况下别崩，其它 Intent 调用也都是这个写法
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                            )
                        }
                    },
                    onOpenReleases = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                            )
                        }
                    },
                    onSendFeedback = {
                        // 版本和机型预填进正文：这两样是排查提醒不响的必要信息，
                        // 让用户自己回想手机型号往往问不出来。
                        // ACTION_SENDTO + mailto: 只会命中邮件类应用，
                        // 不像 ACTION_SEND 会弹出一堆能分享的 App。
                        val body = "\n\n---\n安服 $versionName" +
                            "\n${Build.MANUFACTURER} ${Build.MODEL}" +
                            "\nAndroid ${Build.VERSION.RELEASE}"
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:$FEEDBACK_EMAIL")
                                    putExtra(Intent.EXTRA_SUBJECT, "安服反馈")
                                    putExtra(Intent.EXTRA_TEXT, body)
                                }
                            )
                        }
                    },
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    notifGranted: Boolean,
    exactGranted: Boolean,
    onRequestNotif: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onOpenExactSettings: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("提醒还没完全打开", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    if (!notifGranted) append("需要允许通知，才能在该吃药时提醒你。")
                    if (!exactGranted) {
                        if (!notifGranted) append("\n")
                        append("还需要允许「精确闹钟」，否则提醒时间可能延迟几分钟到几十分钟。")
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!notifGranted) {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) onRequestNotif()
                        else onOpenNotifSettings()
                    }) { Text("开启通知") }
                }
                if (!exactGranted) {
                    TextButton(onClick = onOpenExactSettings) { Text("开启精确闹钟") }
                }
            }
        }
    }
}
