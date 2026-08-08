# ChaoxingSign 项目交接文档（2026-08-09）

> 新会话继续时：读本文件即可掌握全部状态。项目根 `E:\Hermes\ChaoxingSign`

## 1. 项目概况

学习通（超星）自动签到 Android 原生 App（Java + XML，Empty Views Activity）。
GitHub 备份：`HEDBS/chaoxing-sign-android`（Public，main 分支，remote=git@github.com:HEDBS/chaoxing-sign-android.git，SSH 443）
Python 协议工具：`E:\Hermes\chaoxing-py\chaoxing_sign.py`（已推送 `HEDBS/chaoxing-sign`）

## 2. 构建/运行环境（重要）

```
$env:JAVA_HOME="E:\AndroidStudio\123\jbr"      # 必须! 系统 JAVA_HOME=JDK8 会导致 Gradle 卡死
$env:ANDROID_HOME="D:\Android\Sdk"
$env:GRADLE_USER_HOME="D:\.gradle"
cd E:\Hermes\ChaoxingSign; .\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```
- 模拟器 AVD `test`（Pixel 7 / API 36 / WHPX），`D:\Android\avd`，启动：`emulator.exe -avd test -gpu auto -no-snapshot -memory 2048 -no-boot-anim`
- adb：`D:\Android\Sdk\platform-tools\adb.exe`
- 代理 Clash Verge `127.0.0.1:7897`（可能被用户关闭；GitHub 直连可能 SSL 失败，走代理或等用户开）
- 阿里云百炼 DashScope 已欠费（视觉分析不可用），UI 验证全用 uiautomator dump + 文本断言

## 3. 已完成功能（全部实测）

| 功能 | 说明 |
|---|---|
| 登录 | fanyalogin DES-ECB + 会话持久化（SharedPreferences，重启自动登录） |
| 课程列表 | RecyclerView，27 门课，活动扫描置顶 + 三态徽标（橙"有签到"/灰"已签到"/无） |
| 普通/拍照签到 | 实测 success |
| 签到码签到 | 数字输入框，checkSignCode→stuSignajax，实测码 1234 → ✅ 签到成功 |
| **手势签到** | **九宫格画板**（GestureView 自定义 View），编码=触摸点编号序列（1-9，对角穿过的中间点自动计入），Z 形=1235789 爆破+实测，重画按钮+中间点自动包含 |
| 位置签到 | 手动输入 + **地图选点**（LocationPickerActivity：WebView 加载 lbs.qq.com/getPoint/，点击地图选点自动回填坐标+地址，GCJ-02 与学习通一致，免 key） |
| 二维码签到 | 手动输 enc |
| 设置页 | 监听开关/延时/保险（**互斥**，保险仅监听下可用）/账号切换/返回按钮/即时保存 |
| 监听服务 | 前台服务 60s 轮询 27 课，**确认制**（通知点确认才签），延时=自动签模式，保险=确认超时兜底，手势/签到码等通知手动签 |
| 多活动检测 | checkActivity 遍历 activelist + **已签活动跳过**（signedActivityIds），同课多活动逐个签（签到码→位置 实测切换） |

## 4. 关键协议信息

- 活动类型 otherId：0=普通(ifphoto=1 拍照)/2=二维码/3=手势/4=位置/5=签到码；status=1 进行中
- 签到流程：preSign(含 analysis 抠 code) → checkSignCode(手势/签到码 result===1) → stuSignajax
- **手势编码**：九宫格 1-9（左上=1 横向），编码=经过点序列，对角/直线穿过的中间点计入（1→9=159，Z 形=1235789）；numberCount=编码位数
- 位置签到：locationRange=500 米范围，提交坐标须在范围内（GCJ-02 系），服务端不返回中心坐标（需地图选点/用户提供）
- 测试课「课程名称」：courseId=259067506, classId=136189124, fid=2339
- 已签过的活动 activeId 见 activelist（历史活动都 status=2 了）

## 5. 未完成任务（按优先级）

1. **位置签到实测**：选点页在模拟器打开着，用户手动点地图选点→确定→回填→签到（活动 3000166639147 已过期 03:22，需重新发一个）
2. **P1 虚拟位置默认值**：设置页存默认坐标（地图选点选默认位置），位置签到自动预填——用户已同意方向
3. **P2 相册传图拍照签到**：云盘上传协议逆向（pan-yz.chaoxing.com，最大壁垒，未开工）
4. **Release APK 签名打包** + **真机测试**
5. **README 更新**（画板/地图选点新功能截图）
6. **监听开关状态持久化**（重启 App 自动恢复监听）
7. 手势编码/协议逆向文档沉淀

## 6. 验证脚本（C:\Users\lenovo\AppData\Local\Temp\）

- hermes-verify-location-picker.py（地图选点 16/16）
- hermes-verify-multi-activity.py（多活动切换 10/10）
- hermes-verify-gesture-reset.py（画板重画+中间点 9/9）
- hermes-verify-gesture-final.py（画板端到端 7/7）
- hermes-verify-sign-label.py / hermes-verify-p0.py / hermes-verify-badge.py 等
复跑：`python <脚本>`（需模拟器在线 + App 已部署）

## 7. 凭据（值不写明文）

- 账号手机号+密码在验证脚本里（本机自用）；DES key 是前端公开常量
- GitHub 身份：Hek1ng <HEDBS@users.noreply.github.com>，push 用 git@github.com（.ssh/config 443）
- 用户学习通真名：王至（河南科技大学），GitHub 用户名 HEDBS

## 8. 用户偏好

- 简体中文；重大功能先给「可行度/难度/壁垒 + P0/P1/P2」分析再动手
- 存储避开 C 盘；PowerShell 内联 python -c 转义坑多，用独立 .py 文件
- 验证用真实模拟器行为（fresh evidence 文化），脚本保留 Temp 可复跑
- 项目收尾发 GitHub（壁垒+解决+方法论复盘）
