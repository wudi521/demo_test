# VelaGate Android 壳子

英文名：**VelaGate**  
包名：`com.velagate.app`  
定位：外观和交互类似 VPN 节点管理器，但**不会建立真实 VPN 隧道**。

## 已实现

- 首次打开没有节点，底部显示“未连接”。
- 支持右上角 `+` 选择 `.conf`；原生层也接收 Android Drag & Drop 的文件 URI。
- 必须同时拥有一份 **欧洲专线 `.conf`** 和与其绑定的 **流量包 `.conf`**，才能完成解析。
- `.conf` 使用 RSA-SHA256 数字签名；APK 只内置公钥，普通自造/修改的 `.conf` 会提示签名失败。
- 欧洲专线文件还会检查 `region=EUROPE`；其他区域即使结构相似也拒绝。
- 解析后显示：流量包、欧洲1~欧洲5、IP、端口、VLESS 标签。
- 节点可以分享、编辑、删除；编辑和删除会真实保存到应用本地数据。
- 分享弹出二维码；二维码内容固定为纯文字：`该线路已被解析，不能再次解析。`
- 连接按钮只改变界面状态并显示“启动服务成功”，不会请求 Android VPN 权限。
- 应用图标为原创 VelaGate 盾牌/线路标识，不仿冒现有 VPN 品牌。

## “同一文件换设备不能再次解析”怎么保证

这个要求如果只靠 APK 本地存储，**无法真正跨设备保证**。把原始文件复制到第二台手机、清除应用数据或重装后，本地状态无法知道另一台设备是否已经使用过。

因此工程里同时提供了 `activation-server/server.py`。正式模式应当：

1. 部署这个服务，并通过 HTTPS 暴露 `/activate`；
2. 在 `gradle.properties` 设置：
   `VELAGATE_ACTIVATION_URL=https://你的域名/activate`
3. 重新编译 APK。

服务端会把两份文件的 `fileId` **原子绑定到首次解析的设备 ID**。同一设备重试为幂等成功；另一设备再次提交任意一份相同文件会返回 `FILE_ALREADY_BOUND` 并拒绝解析。

当前 `VELAGATE_ACTIVATION_URL` 留空时，APK 使用“本机演示绑定”：同一次安装中可防止重复解析，但**不能替代跨设备服务端唯一绑定**。

## 两份测试配置

位于 `configs/`：

- `欧洲专线.conf`
- `流量包1000G.conf`

这两份已经使用独立的 RSA 私钥正式签名，并且流量包中的 `routeFileId` 与欧洲专线文件严格配对。

> 私钥**没有放进 Android 工程压缩包，也没有打进 APK**；APK 内只有公钥。后续如果要批量签发新的 `.conf`，应单独保管签发私钥。

## 本地构建 APK

要求 Android Studio / Android SDK 35 / JDK 17。

最简单：用 Android Studio 打开项目根目录，等待 Gradle Sync 完成后执行：

`Build > Build APK(s)`

命令行环境已有 Gradle 时：

```bash
gradle :app:assembleDebug
```

输出：

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions 云编译

项目已包含 `.github/workflows/build-apk.yml`。push 到 `velagate` 分支或手动运行工作流，会生成 `VelaGate-debug-apk` 工件。

## 激活服务启动

仅依赖 Python 标准库：

```bash
cd activation-server
python3 server.py --host 0.0.0.0 --port 8787
```

健康检查：`GET /health`。正式环境建议放在 Nginx/Caddy 后并启用 HTTPS。
