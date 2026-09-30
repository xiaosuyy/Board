param(
    [string]$Device = "emulator-5554",
    [string]$Code = "",
    [switch]$Globe,
    [string]$Shot = ""
)
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "E:\OpenCode\blueboard"

# 模拟器（MuMu 900x1600）竖屏字母键中心坐标
$key = @{
    'q' = @(52, 1217);  'w' = @(140, 1217); 'e' = @(228, 1217); 'r' = @(317, 1217); 't' = @(406, 1217)
    'y' = @(494, 1217); 'u' = @(582, 1217); 'i' = @(670, 1217); 'o' = @(759, 1217); 'p' = @(848, 1217)
    'a' = @(96, 1301);  's' = @(184, 1301); 'd' = @(272, 1301); 'f' = @(361, 1301); 'g' = @(450, 1301)
    'h' = @(538, 1301); 'j' = @(626, 1301); 'k' = @(714, 1301); 'l' = @(803, 1301)
    'z' = @(184, 1418); 'x' = @(272, 1418); 'c' = @(361, 1418); 'v' = @(450, 1418); 'b' = @(538, 1418)
    'n' = @(626, 1418); 'm' = @(714, 1418)
}

function Tap([int]$x, [int]$y, [int]$ms = 400) {
    & $adb -s $Device shell input tap $x $y 2>&1 | Out-Null
    Start-Sleep -Milliseconds $ms
}

function DumpXml([string]$name) {
    & $adb -s $Device shell uiautomator dump "/sdcard/$name.xml" 2>&1 | Out-Null
    & $adb -s $Device pull "/sdcard/$name.xml" "$root\_probe\$name.xml" 2>&1 | Out-Null
    return [IO.File]::ReadAllText("$root\_probe\$name.xml", [Text.Encoding]::UTF8)
}

# 1) 打开键盘：App 首页 → 同步页 → 地址输入框（第一阶段验证过的可靠序列）
& $adb -s $Device shell "am force-stop com.board.ime" 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Device shell "am start -n com.board.ime/.settings.BoardSettingsActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
& $adb -s $Device shell input swipe 450 1200 450 700 300 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Device shell input tap 444 1582 2>&1 | Out-Null
Start-Sleep -Seconds 4
& $adb -s $Device shell input tap 450 814 2>&1 | Out-Null
Start-Sleep -Seconds 6
$shown = (& $adb -s $Device shell "dumpsys input_method | grep mInputShown" 2>&1 | Select-Object -First 1)
Write-Host ("keyboard shown: " + ($shown -match 'true'))

# 2) 可选：点语言键（地球）切中英
if ($Globe) {
    Write-Host "点语言键 (52,1500)"
    Tap 52 1500 1500
}

# 3) 可选：输入字母
if ($Code -ne "") {
    Write-Host "输入 $Code"
    foreach ($ch in $Code.ToCharArray()) {
        $k = [string]$ch
        if ($key.ContainsKey($k)) { Tap $key[$k][0] $key[$k][1] 550 }
    }
    Start-Sleep -Seconds 2
}

# 4) 可选：截图
if ($Shot -ne "") {
    & $adb -s $Device shell screencap -p /sdcard/shot.png 2>&1 | Out-Null
    & $adb -s $Device pull /sdcard/shot.png "$root\_probe\$Shot" 2>&1 | Out-Null
    Write-Host "截图: _probe\$Shot"
}
