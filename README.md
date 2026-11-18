# SMB 播放器（第一版原型）

这是一个最简单的安卓本地网络视频播放器原型：用 VLC 内核播放 Windows SMB 共享里的视频，支持 RM/RMVB、MPEG-1/2、MKV、MP4 等常见格式。

本项目的代码只需要上传到 GitHub，由 GitHub Actions 在云端编译出 APK。你的电脑不需要安装 Android Studio、JDK 或任何 SDK。

---

## 一、把项目上传到你的 GitHub 仓库

你的仓库地址（示例）：
`https://github.com/zhrker/adv-player-2026`

1. 在本机解压/打开这个 `adv-player` 文件夹。
2. 打开你的仓库网页，点 **Add file → Upload files**。
3. 把 `adv-player` 文件夹里的**全部文件和子文件夹**拖到网页上。
   - 尤其注意隐藏文件夹 `.github`，里面是云端构建脚本 `build.yml`，**不能少**。
   - 如果网页拖不进隐藏文件夹：点 **Add file → Create new file**，在文件名框里输入 `.github/workflows/build.yml`，然后把本文件夹里 `build.yml` 的内容复制粘贴进去。
4. 滚动到页面底部，点 **Commit changes**（提交更改）。

提交后，云端会自动开始编译。

## 二、查看构建并下载 APK

1. 在仓库页面点上方 **Actions** 标签。
2. 第一次进入时，如果看到绿色按钮 **"I understand my workflows, go ahead and enable them"**，点它启用。
3. 你会看到一次正在运行的构建，名字叫 **Build SMB Player APK**。首次构建需要下载依赖，大约 10–20 分钟。
4. 构建完成后（显示绿色勾），点进这次构建，在页面底部 **Artifacts** 区域下载 `smb-player-debug`（一个 ZIP）。
5. 解压 ZIP，得到 `app-debug.apk`。

## 三、安装到手机

1. 把 `app-debug.apk` 用数据线、微信、网盘或手机浏览器传到安卓手机。
2. 在手机上：设置 → 安全/隐私 → **安装未知应用** → 给你用来打开 APK 的应用（文件管理器或浏览器）开权限。
3. 点击 APK 安装。Play Protect 提示"未知开发者"时，选"仍要安装"。
4. 打开 APP，默认名字是 **SMB 播放器**。

## 四、怎么播放

在 APP 里填三项：

- **SMB 路径**：例如 `\\192.168.1.10\视频\电影.rmvb`
  也可以填 `smb://192.168.1.10/视频/电影.rmvb`
- **用户名**：Windows 共享账号，匿名访问可留空
- **密码**：对应密码

点 **播放**。进度条可以拖动，点 **停止** 结束。

如果播放失败，通常是：IP 不对、共享没开、账号密码错、或文件名带特殊字符。

## 五、后续修改

你告诉我"哪里不好用"，我改代码。你只要把改好的文件重新上传到仓库，Actions 会再次自动出包，你重新下载安装即可。
