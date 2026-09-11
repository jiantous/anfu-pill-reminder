# 安服 UI 改造方案（2026-09 批准版）

背景：1.1.6 已做过一轮 M3 Expressive 风格升级，但动效层缺失、视觉密度偏高、
Expressive 新组件未使用。本方案在不动业务逻辑的前提下做三阶段渐进改造。

已确认的方向性决策：
- 节奏：渐进式三阶段，每阶段出可安装 APK，真机确认后进入下一阶段
- 视觉基调：更激进的 M3 Expressive（MaterialExpressiveTheme 官方形状/动效系统）
- 药品 8 色系统：保留，但 Android 12+ 用 harmonize 跟随壁纸取色
- 编辑页形态：渐进披露（必填常驻 + 更多设置折叠区）

明确不动：MedViewModel、data 层、ScheduleEngine、通知系统、备份逻辑、
minSdk 24、现有全部测试（7 个测试文件必须保持全绿）。

---

## 阶段一：基础重塑

- [x] 1.1 ~~切换 MaterialExpressiveTheme()~~ → **已回退**：material3 1.4.0 中该 API 仍为
      internal（JVM 名带 $material3 后缀，1.5.0 才转正）。保留手写 Expressive 形状
      （PillShapes 恢复并加注释说明），BOM 升到 1.5+ 后再切官方 API
- [x] 1.2 NavHost 转场：tab 切换 fade；子页面水平滑入滑出（用 targetState 判断路由）
- [x] 1.3 LazyColumn 加 animateItem()（Today 三组 + Medications 两组）
- [x] 1.4 顶栏接 enterAlwaysScrollBehavior（三个根 tab 通过 nestedScroll 接入）
- [x] 1.5 FAB 滚动收起（用 scrollBehavior.collapsedFraction 驱动）
- [x] 1.6 密度调优：卡片 padding 16→20dp、列表间距 12→16dp
- [x] 1.7 错过卡片去 alpha hack：统一卡底 + error 色左边条（drawBehind）

验收：真机 APK（tab 转场/打卡动画/顶栏反馈/uiScale 80% 与 130% 两档）；
./gradlew testDebugUnitTest 全绿 ✅（2026-09-11）

## 阶段二：编辑页重构 + 色彩 Harmonize

- [~] 2.1 ~~EditMedicationScreen 渐进披露~~ → **已回退（用户真机验收后否决）**：
      折叠区让低频设置多一步点击、常驻底栏压缩了滚动区，对个人用药场景
      "所有设置一目了然"比"首屏极简"更重要。编辑页保持全展开单页滚动。
      已记录：今后编辑页只做控件语言/间距层面的打磨，不再改信息架构
- [~] 2.2 ~~保存按钮常驻底栏~~ → **随 2.1 一并回退**，恢复顶栏保存 + 底部大按钮
- [x] 2.3 药品 8 色 harmonize：自实现 HSL 色相调和（不引入 material 库依赖），
      向 colorScheme.primary 走 1/3 色相 + 30% 饱和度牵引，仅 Android 12+
- [x] 2.4 空状态统一：药箱/统计页补齐引导文案

验收：测试全绿 ✅；2.3/2.4 待真机确认。

## 阶段三：细节清偿 + 情感化收尾

- [x] 3.1 MiniBarChart 柱子底角改方角（贴基线，只保留顶部圆角）
- [x] 3.2 SettingsSection/SwitchRow 沉淀到 ui/components/SettingsSection.kt，
      设置页私有副本已删（编辑页 SettingCard 同节奏，下次统一替换）
- [x] 3.3 子页面顶栏去掉手动 surface 容器色（编辑/设置/关于/备份 4 页），
      恢复 M3 默认透明顶栏，edge-to-edge 生效
- [x] 3.4 全部完成庆祝动效：进度卡 MediumBouncy 弹簧 scale 1→1.04→1，
      仅在"刚达成全完成"那一刻触发（进页面已是完成态不弹），文案加"安心休息"

验收：编译+测试全绿 ✅（2026-09-11 19:55）；待真机走查。

---

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| Expressive API 与 BOM 2026.06.01 兼容性 | material3 1.4.x 已稳定；编译报缺失则回退手写全套 Expressive shapes，方案结构不变 |
| animateItem() 跨分组移动限制 | 打卡先原地变完成态（spring 动画），列表重组用 fade，M3 官方推荐处理 |
| 转场 vs 自定义 NavHostController + saveState | 转场是 NavHost 参数不碰控制器；tab 状态保持专项回归 |
| 编辑页重构回归面大 | 两步提交：先布局重排再折叠区，每步独立可回滚 |

## 借鉴映射

- 动效/形状：M3 Expressive 官方 motion 规范 + Now in Android
- 色彩 harmonize：Seal
- 密度/呼吸感：Read You
- 编辑页表单：Reply
