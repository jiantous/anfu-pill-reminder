package com.jian.pillreminder.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 备份文件的内容。带 version 便于以后格式升级时兼容旧备份。
 */
@Serializable
data class BackupFile(
    /** 备份格式版本，与 App 版本无关。 */
    val version: Int = CURRENT_VERSION,
    /** 导出时间，ISO-8601 本地时间，仅用于展示。 */
    val exportedAt: String,
    /** 导出时的 App 版本名，便于排查问题。 */
    val appVersion: String = "",
    val medications: List<Medication> = emptyList(),
    val logs: List<DoseLog> = emptyList(),
    val snoozeMinutes: Int = 10
) {
    companion object {
        /**
         * 1 = 首版，iconIndex 指向旧的 Material 图标表
         * 2 = iconIndex 指向按剂型分类的新图标表
         */
        const val CURRENT_VERSION = 2
    }
}

/** 导入时的冲突处理方式。 */
enum class ImportMode {
    /** 用备份完全替换当前数据。 */
    REPLACE,

    /** 两边都保留；同一条服药记录以时间更新的为准。 */
    MERGE
}

/** 备份文件的摘要，用于导入前给用户看清"里面有什么"。 */
data class BackupSummary(
    val exportedAt: String,
    val medicationCount: Int,
    val logCount: Int,
    val medicationNames: List<String>,
    val version: Int
)

object BackupManager {

    private const val TAG = "PillBackup"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 备份文件名：安服备份_2026-08-04_2146.json */
    fun suggestFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "安服备份_${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))}.json"

    fun buildBackup(data: AppData, appVersion: String, now: LocalDateTime = LocalDateTime.now()): String {
        val backup = BackupFile(
            exportedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            appVersion = appVersion,
            medications = data.medications,
            logs = data.logs,
            snoozeMinutes = data.snoozeMinutes
        )
        return json.encodeToString(backup)
    }

    /**
     * 写入到用户先前授权的文件夹（云盘同步目录）。
     * 返回写入的文件名；失败返回 null。
     */
    fun writeToFolder(
        context: Context,
        folderUri: Uri,
        content: String,
        fileName: String
    ): Result<String> = runCatching {
        val dir = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("无法访问所选文件夹，可能已被删除或权限已失效")
        if (!dir.canWrite()) error("对所选文件夹没有写入权限，请重新选择")

        // 同名文件先删掉，避免云盘目录里堆出 xxx(1).json
        dir.findFile(fileName)?.delete()

        val file = dir.createFile("application/json", fileName)
            ?: error("在所选文件夹里创建文件失败")

        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(content.toByteArray())
        } ?: error("打开输出流失败")

        fileName
    }.onFailure { android.util.Log.e(TAG, "写入备份到文件夹失败", it) }

    /** 写入到用户通过「另存为」选中的具体文件。 */
    fun writeToFile(context: Context, fileUri: Uri, content: String): Result<Unit> =
        runCatching {
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { out ->
                out.write(content.toByteArray())
            } ?: error("打开输出流失败")
        }.onFailure { android.util.Log.e(TAG, "写入备份文件失败", it) }

    fun readBackup(context: Context, uri: Uri): Result<BackupFile> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("读不到这个文件")
        val backup = json.decodeFromString<BackupFile>(text)
        if (backup.version > BackupFile.CURRENT_VERSION) {
            error("这个备份来自更新版本的安服（格式 v${backup.version}），请先升级 App")
        }
        upgrade(backup)
    }.onFailure { android.util.Log.e(TAG, "读取备份失败", it) }

    /**
     * 把旧格式的备份升到当前格式。
     * 换手机时很可能是"旧版导出、新版导入"，不升级的话药品图标会错位。
     */
    internal fun upgrade(backup: BackupFile): BackupFile {
        if (backup.version >= BackupFile.CURRENT_VERSION) return backup

        var b = backup
        // v1 → v2：图标表换成按剂型分类，下标含义变了
        if (b.version < 2) {
            b = b.copy(
                medications = b.medications.map {
                    it.copy(iconIndex = mapLegacyIconIndex(it.iconIndex))
                }
            )
        }
        return b.copy(version = BackupFile.CURRENT_VERSION)
    }

    fun summarize(backup: BackupFile) = BackupSummary(
        exportedAt = runCatching {
            LocalDateTime.parse(backup.exportedAt)
                .format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 HH:mm"))
        }.getOrDefault(backup.exportedAt),
        medicationCount = backup.medications.size,
        logCount = backup.logs.size,
        medicationNames = backup.medications.map { it.name }.filter { it.isNotBlank() },
        version = backup.version
    )

    /**
     * 把备份合并进当前数据。
     *
     * REPLACE：直接用备份内容。
     * MERGE：药品按 id 去重（备份优先，因为通常备份来自"主力手机"）；
     *        服药记录按 (药, 日期, 时刻) 去重，取 actedAtMillis 更晚的那条。
     */
    fun apply(current: AppData, backup: BackupFile, mode: ImportMode): AppData = when (mode) {
        ImportMode.REPLACE -> current.copy(
            medications = backup.medications,
            logs = backup.logs,
            snoozeMinutes = backup.snoozeMinutes
        )

        ImportMode.MERGE -> {
            val medsById = LinkedHashMap<String, Medication>()
            current.medications.forEach { medsById[it.id] = it }
            backup.medications.forEach { medsById[it.id] = it }

            val logsByKey = LinkedHashMap<String, DoseLog>()
            (current.logs + backup.logs).forEach { log ->
                val key = "${log.medicationId}|${log.date}|${log.time.hour}:${log.time.minute}"
                val existing = logsByKey[key]
                if (existing == null || log.actedAtMillis >= existing.actedAtMillis) {
                    logsByKey[key] = log
                }
            }

            // 合并后只保留仍有对应药品的记录，避免残留孤儿记录
            val validIds = medsById.keys
            current.copy(
                medications = medsById.values.toList(),
                logs = logsByKey.values.filter { it.medicationId in validIds },
                snoozeMinutes = backup.snoozeMinutes
            )
        }
    }

    /** 距上次备份多少天；从未备份返回 null。 */
    fun daysSince(lastBackupDate: String?, today: LocalDate = LocalDate.now()): Long? {
        val date = lastBackupDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(date, today).coerceAtLeast(0)
    }
}
