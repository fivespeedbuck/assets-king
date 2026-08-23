# 2026-08-22 主页金库 P0 与模块离线交接

## 边界

- 唯一工作区：`D:\assets-king-codex-recovery`
- 分支：`codex/recovery-20260820`
- 基线 HEAD：`4f3c6097cb9aa602e184244b07845bea960fa134`
- 包名：`com.assetsking.app.recovery`
- 用户休息期间未安装 APK、未运行 ADB、未操作 iQOO、未运行 `connectedAndroidTest`。
- `D:\assets-king` 未修改、未构建、未执行 Git 操作。

## 本批实现

1. 金库卡
   - 统一为“入库状态 / 待确认 / 需核对”三个完整点击区。
   - `NEW` 原始通知计入待确认数量；点击先调用 `processNotifications()` 重试，再打开待确认箱。
   - 实际页面与设置页共同观察 `VaultRuntimeStatus`；恢复/落库失败显示错误，不能继续报正常。
   - 原始证据成功持久化后立即安排系统提醒；提醒数量合并 `PENDING_CONFIRMATION + NEW`，即使后续解析失败也不会静默。
   - 短信接收和手动重试失败会进入可见错误态；只有系统通知真正发出后才记录“已提醒”，通知失败仍可重试。
2. 监听恢复
   - 当前通知栏证据逐条落库并解析完成后才可判恢复成功。
   - 恢复 Job 绑定连接代次；断开/销毁取消旧 Job，旧连接不得推进 `lastListenerHealthyAt`。
   - 短信恢复需要 `RECEIVE_SMS + READ_SMS`，主页、设置、Onboarding 口径一致。
   - Android 34+ “立即恢复”先按组件解绑，300ms 后重绑；更低版本安全退化为请求重绑。
   - Room `IGNORE` 返回 rowId；重复通知不会推进 `lastReceivedAt`。
3. 首页结构与模块
   - 移除“总览/日期”顶栏，页面从财务总览卡开始。
   - 预算宽卡；相邻待报销/周期扣款半宽；去掉卡片双层 padding。
   - 近 7 日显示总额、必要、非必要，并带 7 个自然日自定义范围下钻流水。
   - 分账户余额进入资产账户列表。
   - 周期扣款只算本月未匹配规则；待报销显示本月待报与已报。
   - 本月还款读取整月贷款计划期次；首页逾期提醒排序修为逾期优先。
   - 模块库 8 项均提供代表性小卡预览并标注“示例”；标题、说明和预览内容来自唯一规格源，整卡与复选框均可添加/隐藏。
4. 自动回归
   - `HomePresentationTest` 覆盖监听正常/断开/恢复中/恢复失败和 7 日必要性口径。
   - `SettingsPresentationTest` 覆盖运行失败分级。
   - `UpcomingRepaymentsUseCaseTest` 覆盖逾期优先排序。
   - `LedgerRepositoryIntegrationTest` 新增重复通知不推进最近入库时间（测试代码已编译，尚未在设备执行）。
   - `PendingNotifierTest` 覆盖 `NEW` 证据计数与瞬时负数防御；`SettingsPresentationTest` 覆盖应用通知权限缺失只作警告。
   - `HomePresentationTest` 固定模块库 8 个键、唯一数量和所有预览字段非空。
5. 设置权限语义与分类管理审计
   - `POST_NOTIFICATIONS` 只控制恢复包向用户发通知，不控制系统通知监听入库；缺失时改为橙色提醒，不再误报红色入库链路故障。
   - 应用通知权限与电池优化豁免在设置页 `ON_RESUME` 时重新读取。
   - `AK-BUG-023` 完整分类管理当前不可达；恢复到设置页与较新的设置职责冲突，恢复到流水页会触碰冻结页。已重新打开，未用错误入口伪报通过。
6. 编辑器历史联想与分期审计
   - 商户、备注输入 1–2 字时从历史流水生成候选，去空去重、前缀优先、排除当前精确值；`TransactionEditorSuggestionsTest` 已通过。
   - `AK-ENH-002` 仅完成历史联想；原始商户别名映射仍需按账户尾号/渠道隔离且可更正/撤销的专用模型。
   - `AK-ENH-003` 本批只读审计后不实施：现有信用卡分期实体不能表达原流水关联、可分本金和审计；复用贷款计划会重复统计负债，必须留待贷款专项做新表与迁移。

## 离线门禁

成功命令：

```text
.\gradlew.bat :app:testDebugUnitTest :core-ui:testDebugUnitTest :core-usecase:testDebugUnitTest :core-database:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
```

- 结果：`BUILD SUCCESSFUL`
- APK：`D:\assets-king-codex-recovery\app\build\outputs\apk\debug\app-debug.apk`
- Debug SHA-256：`B8D48CFCE8C6AB8C5661DAFA97110A2D198078652B0ED919993FAA3E6549954F`

## Release 身份隔离门禁

- 恢复分支所有变体固定为 `com.assetsking.app.recovery` / `0.1.0-recovery` / “资产大王·恢复验收”；默认 Release 不再误产正式包。
- `scripts/verify-recovery-apk.ps1` 从 APK 实体校验包名、版本和 debuggable：Debug 为 true，unsigned Release 为 false。
- `assembleRelease` 与 Release Lint Vital 成功；unsigned Release SHA-256 `4CE5423E3BD3AD7ED5E66D83E5E56396A90187CF2D34D4842FEAF0904CBB36D1`。
- `apksigner verify` 明确不通过，当前 Release 未签名、不可交付；最终签名身份、真机安装/升级和完整 Release 回归仍待完成。
- `.gitignore` 已拒绝 `*.jks`、`*.keystore` 与 `keystore.properties`；本批没有创建密钥或密码。

## 下一步（用户醒后只安装一次）

1. 用户本人处理 vivo“安全守护”外部来源勾选，安装当前单一 APK。
2. 先恢复/写入 12 个月正常验收数据并复核统计 3/6/12 月、双环和趋势。
3. 连续验收贷款、设置、主页：窄屏布局、三个金库点击区、8 个模块的内容/空态/下钻/拖动。
4. 执行新增数据库 instrumentation；验证短信双权限切换、补收失败与 NEW 重试。
5. 完成 iQOO 15U 划掉、锁屏数小时、重启、真实短信和支付通知的 P0 长时证据。
6. 流水页解冻后，按 298 问决定并恢复完整分类管理入口；在此之前保持 `AK-BUG-023` 未完成。

不能把本批离线门禁或 APK 产出宣布为完整 goal 完成。
