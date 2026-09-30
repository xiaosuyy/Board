param(
  [Parameter(Mandatory = $true)][string]$Code,
  [string]$Out = "E:\OpenCode\blueboard\_probe\type.png",
  [string]$Device = "3B162E000FF00000",
  [switch]$NoClear
)
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 真机 Board 输入法键盘坐标（由截图像素分析得到）
$key = @{
  'q' = @(71, 2023);  'w' = @(190, 2023); 'e' = @(310, 2023); 'r' = @(429, 2023); 't' = @(548, 2023)
  'y' = @(667, 2023); 'u' = @(786, 2023); 'i' = @(906, 2023); 'o' = @(1025, 2023); 'p' = @(1144, 2023)
  'a' = @(131, 2196); 's' = @(250, 2196); 'd' = @(369, 2196); 'f' = @(488, 2196); 'g' = @(608, 2196)
  'h' = @(727, 2196); 'j' = @(846, 2196); 'k' = @(965, 2196); 'l' = @(1084, 2196)
  'z' = @(250, 2369); 'x' = @(369, 2369); 'c' = @(488, 2369); 'v' = @(608, 2369); 'b' = @(727, 2369)
  'n' = @(846, 2369); 'm' = @(965, 2369)
}
function Tap([int]$x, [int]$y, [int]$ms = 350) {
  & $adb -s $Device shell input tap $x $y 2>&1 | Out-Null
  Start-Sleep -Milliseconds $ms
}
# 先进入系统设置的「搜索」界面（首页搜索框在 602,530），此时输入框移到顶部 y≈212
Write-Host "进入设置搜索界面…"
Tap 602 530 2000
# 确认键盘在屏幕上
$shown = (& $adb -s $Device shell "dumpsys input_method | grep mInputShown" 2>&1 | Select-Object -First 1)
if ($shown -notmatch "true") {
  Write-Host "键盘未显示，点击输入框唤起…"
  Tap 533 212 1500
}
if (-not $NoClear) {
  Tap 965 212 800      # 清空输入框（✕）
  Tap 533 212 1200     # 重新聚焦
}
Write-Host "输入: $Code"
foreach ($ch in $Code.ToCharArray()) {
  $k = [string]::new($ch)
  if ($key.ContainsKey($k)) { Tap $key[$k][0] $key[$k][1] 550 } else { Write-Host "  (跳过未知键 '$k')" }
}
Start-Sleep -Seconds 2
& $adb -s $Device shell screencap -p /sdcard/type.png 2>&1 | Out-Null
& $adb -s $Device pull /sdcard/type.png $Out 2>&1 | Out-Null
Write-Host "截图: $Out"
