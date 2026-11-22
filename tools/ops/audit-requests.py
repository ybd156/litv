"""核对用户提过的每一条需求，在代码里是否真的存在。

只输出事实：文件里有没有这个标记。不做"应该做完了"这类推断。
"""
import io
import os
import subprocess

ROOT = os.getcwd()


def has(path, needle):
    p = os.path.join(ROOT, path)
    if not os.path.exists(p):
        return None  # 文件不存在
    try:
        s = io.open(p, encoding="utf-8", errors="ignore").read()
    except Exception:
        return None
    return needle in s


CHECKS = [
    ("keystore 密码 LITV156980 + v1/v2/v3 签名",
     "android/app/build.gradle.kts", "LITV156980"),
    ("v3 签名开关",
     "android/app/build.gradle.kts", "enableV3Signing = true"),
    ("Release 工作流出包",
     ".github/workflows/release.yml", "gh release create"),
    ("端口被占用自动顺延",
     "server/cmd/litvd/main.go", "listenWithFallback"),
    ("手机竖屏布局（layout）",
     "android/app/src/main/res/layout/activity_phone_home.xml", "<"),
    ("CHANGELOG",
     "CHANGELOG.md", "## [0.0.5]"),
    ("设备页 pending 404 修复",
     "admin/src/api/modules/devices.ts", "status: 'pending'"),
    ("iPhone 未批准不能进（isBound）",
     "ios/LITVApp/Sources/Session.swift", "isBound"),
    ("心跳 57 年前修复（设备页）",
     "admin/src/views/DevicesView.vue", "'从未'"),
    ("心跳 57 年前修复（概览活动）",
     "admin/src/views/DashboardView.vue", "'—'"),
    ("手机播放页：名称→视频→节目单→菜单栏",
     "android/app/src/main/res/layout-port/activity_phone_player.xml", "epgList"),
    ("底部菜单栏（首页）",
     "android/app/src/main/res/layout/activity_phone_home.xml", "tabRemote"),
    ("底部菜单栏（设置页）",
     "android/app/src/main/res/layout/activity_settings.xml", "view_bottom_tabs"),
    ("底部菜单栏（遥控器页）",
     "android/app/src/main/res/layout/activity_remote.xml", "view_bottom_tabs"),
    ("绑定页域名输入",
     "android/app/src/main/res/layout-port/activity_bind.xml", "domainInput"),
    ("源级 UA（服务端字段）",
     "server/internal/store/models.go", "UserAgent"),
    ("源级 UA（管理台表单）",
     "admin/src/components/SourceFormModal.vue", "user_agent"),
    ("直连播放（服务端）",
     "server/internal/api/handlers_client_play.go", '"mode":      "direct"'),
    ("直连播放（客户端开关）",
     "android/app/src/main/java/com/litv/tv/data/Prefs.kt", "directMode"),
    ("台标管理（服务端）",
     "server/internal/logo/logo.go", "func (s *Store) SaveFromURL"),
    ("频道按源分组（服务端过滤）",
     "server/internal/store/repo.go", "SourceID"),
    ("频道按源分组（管理台列）",
     "admin/src/views/ChannelsView.vue", "sourceNameOf"),
    ("登录页（仿飞牛影视）",
     "admin/src/views/LoginView.vue", "使用 NAS 登录"),
    ("删除 APK 逆向报告",
     "docs/research/03-apk-re.md", ""),
    ("三列联动首页（该布局已撤，改为直接进播放页）",
     "android/app/src/main/res/layout/activity_home.xml", ""),
    ("节目单：回看角标 + 简介走马灯",
     "android/app/src/main/res/layout/item_epg.xml", "@+id/action"),
    ("设置分主题弹层（弹层已删）",
     "android/app/src/main/java/com/litv/tv/ui/SettingsDialog.kt", ""),
    ("按 MENU 打开设置",
     "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt", "KeyEvent.KEYCODE_MENU"),
    ("开机自动播放上次频道（偏好项）",
     "android/app/src/main/java/com/litv/tv/data/Prefs.kt", "autoPlayLast"),
    ("开机自动播放上次频道（启动器读取点）",
     "android/app/src/main/java/com/litv/tv/ui/LauncherActivity.kt", "autoPlayLast"),
    ("换台加速：3s 超时选项（默认已改为 15s）",
     "android/app/src/main/java/com/litv/tv/data/PlaybackDefaults.kt", "START_TIMEOUT_OPTIONS_MS"),
    ("换台加速：聚焦预取",
     "android/app/src/main/java/com/litv/tv/data/Api.kt", "prefetchPlay"),
    ("硬解 UA 修复（Exo）",
     "android/app/src/main/java/com/litv/tv/player/ExoEngine.kt", 'headers["User-Agent"]?.takeIf'),
    ("iOS 绑定页地址分框",
     "ios/LITVApp/Sources/BindView.swift", "ipFields"),
    ("电视误判成手机修复",
     "android/app/src/main/java/com/litv/tv/ui/LauncherActivity.kt", "fromLeanback"),
    ("界面模式手动开关",
     "android/app/src/main/res/layout/activity_settings.xml", "rowUiMode"),
]

print("=" * 72)
for name, path, needle in CHECKS:
    r = has(path, needle)
    if needle == "":
        # 反向检查：这条是「文件/布局应当已经不存在」（删掉的死码、撤下的方案）。
        # 注意必须先判 needle，再判 r is None——否则"已删除"会被报成"文件不存在"，
        # 把一个正确的删除显示成异常（这个顺序 bug 存在了很久）。
        status = "已删除（符合预期）" if r is None else "文件还在（未删除）"
    elif r is None:
        status = "文件不存在"
    elif r:
        status = "有"
    else:
        status = "❌ 没有"
    print(f"  {status:12} {name}")

print("=" * 72)
print("\n=== git 状态 ===")
try:
    out = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True).stdout
except (FileNotFoundError, OSError):
    # 本机 git 可能不在 PATH（例如 Windows 上装在独立目录）。这里只影响末尾那段
    # 附带信息，不该让整个走查脚本以 traceback 结束。
    out = None
    print("（本机找不到 git，跳过工作树状态）")
if out is not None:
    print("未提交文件数:", len([l for l in out.split("\n") if l.strip()]))
    for l in out.split("\n")[:12]:
        if l.strip():
            print("  ", l)
