#!/usr/bin/env pwsh
# 持续监测电视播放日志，统计卡顿指标。
# 用法：tools\ops\watch-tv-play.ps1 [-Minutes 120] [-IntervalSec 300]
#
# 为什么要单独一个脚本：以前靠人肉"看一眼有没有卡"，结论随手感走，
# 而且短样本（150 秒）根本说明不了问题。这里按固定窗口滚动统计，
# 把「每分钟重缓冲次数」这类可比的数落下来。

param(
    [int]$Minutes = 120,
    [int]$IntervalSec = 300,
    [string]$TvAddr = "192.168.150.13:5555",
    [string]$Adb = "C:\Users\Z\.litv-tools\platform-tools\adb.exe"
)

$logPath = "/sdcard/Android/data/com.litv.tv/files/play.log"
$local = Join-Path $env:TEMP "litv-watch.log"
$report = Join-Path $PSScriptRoot "watch-report.txt"

function Now { (Get-Date).ToString("yyyy-MM-dd HH:mm:ss") }

"=== LITV 电视播放监测开始 $(Now) / 共 $Minutes 分钟 / 每 $IntervalSec 秒 ===" |
    Tee-Object -FilePath $report -Append

$prevLines = 0
$endAt = (Get-Date).AddMinutes($Minutes)
$round = 0
$badRounds = 0

while ((Get-Date) -lt $endAt) {
    $round++
    & $Adb connect $TvAddr 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $Adb -s $TvAddr pull $logPath $local 2>&1 | Out-Null

    if (-not (Test-Path $local)) {
        "$(Now) 第${round}轮：拉不到日志（电视离线/未在播）" | Tee-Object -FilePath $report -Append
        Start-Sleep -Seconds $IntervalSec
        continue
    }

    $lines = Get-Content $local -Encoding UTF8
    $total = $lines.Count
    if ($total -lt $prevLines) { $prevLines = 0 }   # 日志被轮转/清空
    $new = @()
    if ($total -gt $prevLines) { $new = $lines[$prevLines..($total - 1)] }
    $prevLines = $total

    $rebuffer = ($new | Select-String -Pattern '重缓冲').Count
    $stall    = ($new | Select-String -Pattern '判定卡死').Count
    $e403     = ($new | Select-String -Pattern '分片 403').Count
    $switch   = ($new | Select-String -Pattern '换内核').Count
    $fallback = ($new | Select-String -Pattern '自动降级').Count
    $slow     = ($new | Select-String -Pattern '加载慢').Count
    $boot     = ($new | Select-String -Pattern '起播「').Count
    $firstFrm = ($new | Select-String -Pattern '首帧已渲染').Count

    # 判据：一个 5 分钟窗口内重缓冲超过 5 次，或出现卡死，算"这一段在卡"
    $flag = ""
    if ($stall -gt 0) { $flag = " ⚠️卡死${stall}次" ; $badRounds++ }
    elseif ($rebuffer -gt 5) { $flag = " ⚠️重缓冲偏多" ; $badRounds++ }

    $line = "$(Now) 第${round}轮 新增${($new.Count)}行 | 起播${boot} 首帧${firstFrm} 重缓冲${rebuffer} 卡死${stall} 403:${e403} 换内核${switch} 降级${fallback} 加载慢${slow}${flag}"
    $line | Tee-Object -FilePath $report -Append

    Start-Sleep -Seconds $IntervalSec
}

"" | Tee-Object -FilePath $report -Append
"=== 监测结束 $(Now) / 异常轮次 $badRounds / 报告：$report ===" |
    Tee-Object -FilePath $report -Append
