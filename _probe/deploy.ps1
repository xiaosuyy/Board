param([string]$Device = "3B162E000FF00000", [int]$WaitSec = 30)
$ErrorActionPreference = "Continue"
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "E:\OpenCode\blueboard"
& $adb -s $Device shell run-as com.board.ime rm -rf cache/rime/log 2>&1 | Out-Null
& $adb -s $Device logcat -c 2>&1 | Out-Null
& $adb -s $Device shell am force-stop com.board.ime 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Device shell "am start -n com.board.ime/.settings.BoardSettingsActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
& $adb -s $Device shell input tap 600 1821 2>&1 | Out-Null   # 「词库」行
Start-Sleep -Seconds 5
& $adb -s $Device shell input swipe 608 2200 608 700 300 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Device shell uiautomator dump /sdcard/d.xml 2>&1 | Out-Null
& $adb -s $Device pull /sdcard/d.xml "$root\_probe\d.xml" 2>&1 | Out-Null
$xml = [IO.File]::ReadAllText("$root\_probe\d.xml", [Text.Encoding]::UTF8)
$m = [regex]::Match($xml, 'text="部署到 Rime"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $m.Success) { $m = [regex]::Match($xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?text="部署到 Rime"') }
if ($m.Success) {
  $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
  $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
  Write-Host "部署按钮 = ($x,$y)"
  & $adb -s $Device shell input tap $x $y 2>&1 | Out-Null
} else {
  Write-Host "未找到按钮，用固定坐标 (608,2294)"
  & $adb -s $Device shell input tap 608 2294 2>&1 | Out-Null
}
Start-Sleep -Seconds $WaitSec
& $adb -s $Device logcat -d -v time 2>&1 | Select-String "BoardDict|BoardRime" | Select-Object -Last 9 | ForEach-Object { $_.Line }
