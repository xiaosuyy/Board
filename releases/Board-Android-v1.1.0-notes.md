# Board Android v1.1.0 正式版

- 日期：2026-09-30
- APK：`releases/Board-Android-v1.1.0.apk`（同时保留 `Board-Android-v1.1.0-rc.apk`）
- 大小 / SHA-256：15,622,845 B（14.9 MB）/ `EDE2C7F1F1A593784837CADF4021204AD5118E44E352AD1A33CB05030D5885D7`
- versionName **1.1.0** ／ versionCode **5**（1 → 2 → 3 → 4 → 5，升级链连续）
- 签名：正式 release 证书 `CN=Board IME, OU=Input Method, O=Board, L=Beijing, ST=Beijing, C=CN`
  证书 SHA-256：`2d7aa06d1fc9b11041978d9651a65ef7bed1da72e902f97e34c68da9b34ffde7`
  （keystore 在仓库之外 `%USERPROFILE%\board-keys\board-release.jks`，并已备份；仓库内不含私钥与口令）
- R8：release 开启代码混淆/收缩（`isMinifyEnabled = true`），仅保留 JNI bridge
  `com.board.ime.engine.rime.RimeNative` 的 native 方法名；`isShrinkResources` 有意关闭
  （Rime schema/词典/OpenCC 位于 `assets/`，由运行时按路径读取，避免误删）

## 主要内容

- **正式 Release 签名**：v1.1.0 起所有正式版本共用同一把 release keystore，可覆盖升级
- **R8 Release 优化**：代码收缩 + 混淆（dex 约 16 MB → 2.56 MB），JNI/Room/WorkManager/OkHttp 规则齐备
- **WebDAV 密码默认隐藏**：打开同步页即打码，右侧眼睛按钮可显示/隐藏，页面重开恢复隐藏；密码不写入日志
- **首次安装默认中文**：全新安装默认中文；用户切换后记住选择；升级安装不覆盖既有偏好
- **Rime/librime 输入体系稳定**：librime 1.16.1，方案 `luna_pinyin_simp`，学习词（userdb）读写正常
- **600k 词库支持**：大型导入词库（60 万 / 25.6 万 / 54.4 万条）经占位方案接入 Rime 候选
- **Clipboard 自动同步**：复制后自动 Push、打开剪贴板自动 Pull；云端/本地 hash 对齐，不重复上传
- **WebDAV 同步**：地址/账号/密码/目录配置、测试连接、立即同步、自动同步
- **存储管理与大词库保护**：清理动作只作用于缓存/无引用旧词库/已删除剪贴板，
  `rime/shared`、`rime/user/build`、`.userdb`、`.dict.yaml.gz` 一律不动
- **Android 真实设备适配**：PLZ110 / Android 16（API 36）实测；minSdk 26；arm64-v8a / armeabi-v7a / x86_64

## 本版本未包含

- **候选分页按钮**：候选栏当前设计为「最多 12 项、可横向拖动」，没有上一页/下一页
- **词库按数据源独立 ON/OFF**：数据源已有 `enabled` 字段，但尚未提供 UI 入口

## 已知遗留问题

存在一个**自 v1.0.0 延续**的、**极低频**的 librime teardown 阶段 native SIGSEGV
（发生在进程被强停/回收时销毁会话的过程中）。目前无法稳定复现（连续 5 次强停未复现），
**不属于 v1.1.0 新增回归**，正常情况下不影响输入使用。计划后续在 v1.1.1 单独处理。

## 封版说明

- 本版本由 RC（`Board-Android-v1.1.0-rc.apk`）直接封版，代码与配置未做任何改动
- v1.0.0 历史提交 `ea70b7b` 与 tag `v1.0.0` 未被修改（APK SHA-256 仍为 `A7DA987B…13F8F`）
