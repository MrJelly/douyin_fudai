# douyin_fudai — 安卓直播福袋助手 & 商品秒杀工具

一款基于 Android **无障碍服务 (AccessibilityService)** + **OpenCV 模板匹配** + **Google ML Kit 离线中文 OCR** 的安卓端自动化辅助工具，支持自动巡航直播间参与超级福袋、直播间商品抢购/秒杀、自动弹幕互动、无障碍防杀守护及中奖邮件通知。

---

## ✨ 功能特性

- **自动巡航与抢超级福袋 (`FudaiEngine`)**
  - 基于有限状态机 (`FsmState`) 驱动：页面识别与导航 → 福袋图标扫描 → 打开详情 → 规则过滤 → 参与抽奖 → 等待开奖 / 切换直播间。
  - **OpenCV 多尺度 + HSV 颜色门控模板匹配 (`SuperFudaiTemplateMatcher`)**：精准识别直播间左上角“超级福袋”图标，排除头像与普通红包的误判。
  - **离线中文 OCR 兜底识别 (`OfflineOcr`)**：内置 Google ML Kit 中文识别模型，无需联网或 Google Play 服务即可识别倒计时、参与人数、中奖弹窗等页面文本。
  - **灵活过滤策略**：支持自定义进房等待时间、最大剩余开奖时间、参与人数上限、命中率下限等筛选条件。
- **直播间商品抢购 / 秒杀 (`ProductSeckillEngine`)**
  - 支持在直播间小黄车/购物袋中按商品序号或关键词、最高价格限制自动监测并快速下单。
- **自动弹幕互动**
  - 支持自定义多条弹幕文案、发送间隔与发送次数。
- **中奖邮件提醒 (`EmailNotifier`)**
  - 原生轻量 SMTP 客户端（支持 TLS 465 / STARTTLS 587，禁止明文回退），开箱支持 QQ、163、126、139、Gmail 邮箱测试与中奖实时推送。
- **无障碍服务防掉线自愈守护 (`AccessibilityRecoveryService`)**
  - 独立 `:recovery` 进程心跳检测，授权 `WRITE_SECURE_SETTINGS` 后可在系统清理导致无障碍掉线时自动重连恢复。

---

## 📂 项目目录结构

```text
.
├── app/
│   ├── build.gradle                         # App 模块构建配置 (Kotlin, ML Kit 中文 OCR, OpenCV 4.10.0)
│   └── src/main/
│       ├── AndroidManifest.xml              # 权限、Activity、无障碍服务与守护进程声明
│       ├── java/com/jelly/fudai/
│       │   ├── MainActivity.kt              # 权限检查与悬浮控制面板启动入口
│       │   ├── FudaiAccessibilityService.kt # 无障碍核心服务、悬浮窗 UI 与配置面板
│       │   ├── FudaiEngine.kt               # 超级福袋自动巡航与参与状态机引擎
│       │   ├── ProductSeckillEngine.kt      # 直播间商品秒杀/抢购引擎
│       │   ├── SuperFudaiTemplateMatcher.kt # OpenCV 超级福袋图标模板匹配器
│       │   ├── OfflineOcr.kt                # Google ML Kit 离线中文 OCR 封装
│       │   ├── EmailNotifier.kt             # 原生 SMTP 邮件通知模块
│       │   ├── AccessibilityRecoveryService.kt # 无障碍服务心跳监控与自愈守护服务
│       │   ├── RecoveryBootReceiver.kt      # 开机与覆盖安装广播接收器
│       │   ├── FsmState.kt                  # 状态机枚举定义
│       │   └── AppLog.kt                    # 运行日志记录工具
│       └── res/                             # 图标、超级福袋匹配模板图片、无障碍配置等资源
├── gradle/wrapper/                          # Gradle Wrapper (Gradle 8.2.1)

├── build.gradle                             # 根项目 Gradle 插件声明
├── settings.gradle                          # 仓库镜像与模块配置
├── gradle.properties                        # JVM 与 AndroidX 属性配置
├── gradlew / gradlew.bat                    # 标准 Gradle Wrapper 启动脚本
├── build-apk.bat                            # Windows 一键编译签名 APK 脚本
└── install-apk.bat                          # Windows 一键通过 ADB 安装并自动授权脚本
```

---

## 🛠️ 编译与安装

### 环境要求

- **操作系统**：Android 11+ (`minSdk 30`, `arm64-v8a`)
- **开发环境**：
  - JDK 17
  - Android SDK (`compileSdk 33`)

### 方式一：使用命令行 / Android Studio 编译

1. 使用 Android Studio 直接打开项目根目录，或使用终端执行：
   ```bash
   # Linux / macOS
   ./gradlew :app:assembleDebug

   # Windows
   .\gradlew.bat :app:assembleDebug
   ```
2. 编译生成的已签名安装包位于：
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

### 方式二：Windows 快捷脚本编译与安装

1. 双击或在终端运行 `build-apk.bat` 编译生成签名 APK。
2. 手机开启 **开发者选项 -> USB 调试** 并连接电脑，运行 `install-apk.bat`：
   - 脚本会自动安装 APK；
   - 自动通过 ADB 授予 `WRITE_SECURE_SETTINGS` 权限并开启无障碍服务；
   - 若提示签名冲突，可执行 `install-apk.bat -Clean` 卸载旧版本后重装。

---

## 📖 使用说明

### 一、安装与授权

1. 安装软件并打开 **抖音福袋助手**。
2. 点击 **“1. 授予悬浮窗权限”**，在系统设置中选择允许。
3. 点击 **“2. 开启无障碍服务”**，在已下载的服务列表中找到本软件并开启。
4. 返回软件，确认状态显示 **“悬浮窗：已授权 / 无障碍：已连接”**。
5. 点击 **“3. 显示悬浮控制面板”**。
6. 打开 **抖音 / 抖音极速版**，并确认账号已登录。
7. 在抖音首页顶部调整标签顺序，将 **关注** 和 **直播** 拖到 **推荐** 左边。

### 二、开始使用

1. 打开抖音或抖音极速版，停留在首页、直播广场或关注页。
2. 点击悬浮窗中的 **“开始”** 按钮。
3. 软件会自动进入直播间、寻找并参与符合筛选条件的超级福袋（或切换到商品抢购模式执行秒杀）。
4. 自动运行期间请勿手动点击、返回或滑动屏幕。
5. 需要停止时，展开悬浮窗并点击 **“结束”**。

### 三、悬浮窗功能说明

- **开始**：启动当前选定模式的自动化流程。
- **结束**：停止自动化流程和自动弹幕。
- **设置**：调整进房等待时间、福袋剩余时间上限、参与人数限制、命中率阈值及邮件通知配置。
- **弹幕**：设置自动发送的弹幕内容、发送间隔和发送次数。
- **收起 / 展开**：折叠或展开悬浮控制面板。

### 四、常见问题排查

- **点击“开始”没有反应**：
  - 确认目标应用（抖音 / 抖音极速版）正在前台运行；
  - 确认主界面无障碍服务状态显示“已连接”；
  - 若系统杀后台导致无障碍断连，可重新开关一次无障碍服务，或使用 `install-apk.bat` 授予 `WRITE_SECURE_SETTINGS` 实现自动恢复。
- **长时间没有中奖**：
  - 中奖结果由平台决定，自动参与不保证中奖。如果长时间未中奖，可以尝试更换设备，或暂停一段时间后再试；这不保证提高中奖概率。
- **邮件提醒测试失败**：
  - 发件箱需开启 SMTP 服务并填写**客户端授权码**（而非邮箱登录密码）。
  - 授权码保存在应用私有偏好设置中，尚未额外加密，系统备份已禁用；请勿在问题反馈中公开授权码。139 邮箱若不支持 TLS 465 / STARTTLS 587，将无法发送通知。

---

## ⚠️ 免责声明

1. 本项目仅用于 Android 无障碍服务 (`AccessibilityService`)、OpenCV 端侧图像处理及 ML Kit 离线 OCR 技术的学习与研究。
2. 请勿将本项目用于任何违反目标平台服务协议或法律法规的用途。使用本项目所产生的一切风险与责任由使用者自行承担。

---

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。
