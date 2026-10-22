# 开发与构建

当前版本：`1.0.0-preview.5` / `10005`。发布包名为 `org.homemusic.player`，调试包增加 `.debug` 后缀。沿用同一签名的更新可覆盖安装，卸载会删除应用私有数据。

## 环境

JDK 21、Android Platform 37.0、Build Tools 36.1.0、Gradle 9.8.0。SDK Manager 的平台包名为 `platforms;android-37.0`。如果当前 AGP 未识别带小版本号的安装目录，在 SDK 的 `platforms` 下将 `android-37.0` 链接或复制为 `android-37`；CI 已处理该兼容目录。源码保留 Tempus v4.30.0 的 GPL-3.0 授权，基础提交为 `c0d6c51a13e88f5b9b62854f765f9fa9da8450c3`。

```sh
./gradlew assembleDegoogledDebug
./gradlew testDegoogledDebugUnitTest testTempusDebugUnitTest lintDegoogledRelease
```

Windows 可使用 `gradlew.bat`，或设置 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```powershell
./build-jiayin.ps1 -Configuration Debug
```

如果 Windows 的 Gradle 子进程无法生成 Android JDK image，可设置 Python 并使用 `-WindowsJdkWorkaround`。生成的 `local-toolchains/` 与 `local.properties` 均不提交。

## 发布签名

自己生成并保管签名文件，通过环境变量提供：

```text
JIAYIN_KEYSTORE_PATH
JIAYIN_STORE_PASSWORD
JIAYIN_KEY_ALIAS
JIAYIN_KEY_PASSWORD
```

```powershell
./build-jiayin.ps1 -Configuration Release
```

不要把签名文件、密码、账户、`.env` 或本地构建产物提交到 Git。CI 只执行测试、lint 和未签名发布构建，不接触发布私钥；可安装的签名 APK 单独通过 Release 提供。

## 数据与兼容性

保留账户隔离的 Room 数据、分块 JSON、下载索引和固定签名升级路径。首次启动服务器和账户为空；已安装用户的本机配置不因通用化而清除。首次连接的自动歌词修复需要用户勾选；此前已启用该能力的旧版本保留原有行为，可在设置关闭。

LocalDatabase、Media3 下载索引与状态是实际下载判据。没有真实设备时，不将模拟器结果写成音频焦点、蓝牙或真实运营商网络的兼容性承诺。

仓库不包含测试服务器以外的真实端点或个人曲库。截图、测试音频与歌词均为生成示例。原始 Tempus 组件中仍有该上游的公开链接与版权署名。
