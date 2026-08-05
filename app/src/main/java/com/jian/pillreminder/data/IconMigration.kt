package com.jian.pillreminder.data

/**
 * 药品图标表换代时的下标映射。
 *
 * 首版图标用的是 Material 通用图标，且混用了两套分类逻辑：
 *   0 药片  1 胶囊  2 滴剂  3 针剂  （剂型）
 *   4 心脏  5 血液  6 保健  7 外用  （适应症）
 *   8 其他
 *
 * 新版统一按剂型分类（见 ui/components/MedIconSet.kt）：
 *   0 圆片  1 胶囊  2 椭圆片  3 颗粒  4 口服液
 *   5 滴剂  6 注射  7 喷雾    8 软膏  9 贴剂
 *
 * `iconIndex` 存的是下标，表一换含义就变了——不映射的话，存量药品会莫名换成
 * 别的形状（比如旧"心脏"(4) 会显示成新的"口服液"）。按语义就近对应，
 * 适应症类没有直接对应的剂型，取该类最常见的剂型。
 *
 * 用在两处：本地数据升级（[MedRepository] 的 schema v0→v1）和
 * 旧备份导入（[BackupManager] 的 v1→v2）。
 */
internal val LEGACY_ICON_MAPPING: IntArray = intArrayOf(
    0,  // 药片 → 圆片
    1,  // 胶囊 → 胶囊
    5,  // 滴剂 → 滴剂
    6,  // 针剂 → 注射
    0,  // 心脏（适应症）→ 圆片，无对应剂型，取最常见的
    4,  // 血液（适应症）→ 口服液，这类多是口服铁剂、补液
    1,  // 保健（适应症）→ 胶囊，保健品多为胶囊
    8,  // 外用 → 软膏
    0   // 其他 → 圆片
)

/** 按旧表下标取新表下标；越界（不该发生）时原样返回，不猜。 */
internal fun mapLegacyIconIndex(old: Int): Int =
    LEGACY_ICON_MAPPING.getOrNull(old) ?: old
