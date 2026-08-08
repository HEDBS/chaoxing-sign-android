# 学习通签到助手 (Android)

基于学习通(超星)公开接口的 Android 签到工具。自动检测课程签到活动，支持一键签到与后台监听自动签到。

> ⚠️ **免责声明**: 本项目仅供学习 Android 开发与 HTTP 协议分析使用。请遵守课堂纪律，勿用本工具规避正常签到。

## 功能

| 功能 | 说明 |
|---|---|
| 登录 | 手机号+密码，DES 加密协议登录，**会话持久化免登录** |
| 课程列表 | RecyclerView 展示全部课程（含班级） |
| 签到页 | 点课程 → 自动检测当前活动 → 按类型签到 |
| 六种签到 | 普通/拍照/位置/手势/签到码/二维码 |
| 监听模式 | 前台服务每 60 秒轮询全部课程，检测到签到自动处理 |
| 延时签到 | 检测到可自动签活动后等待 N 秒再签 |
| 确认保险 | 通知提醒 + 超时未确认自动签（兜底） |
| 账号切换 | 设置页一键退出重登 |

## 界面

| 课程列表 | 签到页 | 设置 |
|---|---|---|
| ![课程列表](docs/screenshot_courses.png) | ![签到页](docs/screenshot_sign.png) | ![设置](docs/screenshot_settings.png) |

## 使用

1. 打开 App，输入学习通账号密码登录（登录态持久化，下次免登录）
2. 课程列表点击课程 → 查看当前是否有签到活动
3. 设置页打开**监听模式**（需通知权限）：
   - 普通/拍照签到 → 自动签到（可配延时秒数）
   - 手势/签到码/位置/二维码 → 通知提醒，点击前往手动签
   - 开启**确认保险** → 通知后超时未确认会自动签

## 技术架构

- **语言**: Java 17 (源码兼容 8+)
- **UI**: XML 布局 + RecyclerView（无 Compose，便于学习）
- **网络**: HttpURLConnection + Cookie 会话
- **协议层**: `ChaoxingApi.java`（登录/课程/活动检测/签到全协议）
- **监听**: 前台服务 `SignMonitorService` + 通知

```
app/src/main/java/com/example/chaoxingsign/
├── ChaoxingApi.java       # 协议层: 登录DES/课程/活动检测/六种签到/会话持久化
├── MainActivity.java      # 登录页 + 课程列表 + 自动登录
├── SignActivity.java      # 签到页: 检测活动 + 按类型签到
├── SettingsActivity.java  # 设置: 监听开关/延时/保险/账号切换
├── CourseAdapter.java     # 课程列表适配器
└── SignMonitorService.java # 前台服务: 60s轮询 + 通知 + 自动签
```

## 构建

```bash
# 需要 JDK 17+ (Android Studio 自带 JBR 也可)
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

- 依赖镜像: 阿里云 Maven (settings.gradle.kts)
- Gradle 9.5 + AGP 9.3 + minSdk 24 / targetSdk 37

## 相关项目

- [chaoxing-sign](https://github.com/HEDBS/chaoxing-sign) — 同协议的 Python 命令行版（含 Termux 手机教程），本项目的协议源头
