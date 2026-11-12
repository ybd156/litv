# tools

开发期的辅助脚本。**不参与构建，CI 也不用它们**（CI 只用 `build/test/` 下的集成测试）。

历史上有 89 个文件，其中 60 多个是一次性的 `patch-*.py`（改完源码就废了）
和 `diag-*.sh`（写死 NAS 路径的临时排查）。2026-11 清理到现在的 16 个，
只保留能反复用的。

## checks/ — 一致性检查

改完代码可以跑，用来发现「改了一处忘了另一处」的问题。

| 脚本 | 检查什么 |
|---|---|
| `check-api-contract.py` | 服务端路由与客户端/管理台调用的接口是否对得上，提前发现 404 |
| `check-swift-balance.py` | Swift 文件括号/引号是否配平（本地编不了 iOS，这是廉价的兜底） |
| `check-swift-init-coverage.py` | `Models.swift` 里的结构体是否都在测试数据里构造过 |

## inspect/ — 分析外部产物

| 脚本 | 用途 |
|---|---|
| `analyze-ref-apks.py` | 拆参考播放器 APK，看它用了哪些类/常量（研究实现方式用） |
| `dump-layout.py` | 反编译 APK 的布局 XML 结构，对比界面设计 |
| `ts-stream-types.py` | 抓一段真实 TS，解析 PAT/PMT 列出每条基本流的 `stream_type` |
| `gen-ios-icon.py` | 从一张图生成 iOS 所需的全部尺寸图标 |

`ts-stream-types.py` 的用法：

```bash
python3 tools/inspect/ts-stream-types.py "https://example.com/playlist.m3u8?token=..."
```

它是查「AV3A 用哪个 stream_type」的工具，输出形如：

```
stream_type=0x1B ( 27)  PID=0x0100  H.264
stream_type=0xD5 (213)  PID=0x0101  未知
```

## ops/ — 对 NAS / 设备操作

**这些会连真实环境**（NAS、电视），跑之前看清楚。

| 脚本 | 作用 |
|---|---|
| `approve-devices.sh` | 批量批准待绑定的设备 |
| `check-approve.sh` | 查某台设备当前的绑定状态 |
| `audit-requests.py` | 拉服务端审计日志，看谁在什么时候做了什么 |
| `clean-workflow-runs.py` | 清理 GitHub Actions 的历史运行记录 |
| `update-downloads.py` | 把 README 的版本号和下载表同步成最新 Release（CI 自动跑） |
| `sweep-channels.sh` | 批量探测频道可用性 |
| `sweep-recursive.sh` | 递归探测（含分组） |
| `tvshot.sh` | adb 截图电视当前画面 |
| `verify-automap.sh` | 验证 EPG 自动匹配的结果 |
| `verify-refresh.sh` | 验证 EPG 刷新链路 |

## 约定

- 新脚本放对应子目录，**不要**再往 `tools/` 根目录堆
- 一次性的东西（改完就没用的 patch、临时排查）**不要提交**，
  跑完删掉或放本地忽略目录
- 写死环境地址的脚本（NAS IP、设备序列号）要注明，别让别人直接跑
