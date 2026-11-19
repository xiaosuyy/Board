param(
    [string]$Device = "emulator-5554",
    [ValidateSet("", "CHINESE", "ENGLISH")][string]$Set = ""
)
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "E:\github\board"

function DumpXml([string]$name) {
    & $adb -s $Device shell uiautomator dump "/sdcard/$name.xml" 2>&1 | Out-Null
    & $adb -s $Device pull "/sdcard/$name.xml" "$root\_probe\$name.xml" 2>&1 | Out-Null
    return [IO.File]::ReadAllText("$root\_probe\$name.xml", [Text.Encoding]::UTF8)
}

# 「输入」页第一组 toggle（同一行的两个 checkable 选项）= 语言；其中一个是 "English"，另一个是中文
function ParseLang([string]$xml) {
    $opts = New-Object System.Collections.ArrayList
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $v = $m.Value
        if ($v -notmatch 'checkable="true"') { continue }
        $b = [regex]::Match($v, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        [void]$opts.Add([pscustomobject]@{
            x       = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)
            y       = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
            text    = [regex]::Match($v, 'text="([^"]*)"').Groups[1].Value
            checked = ([regex]::Match($v, 'checked="([^"]*)"').Groups[1].Value -eq 'true')
        })
    }
    if ($opts.Count -lt 2) { return $null }
    $minY = ($opts | Measure-Object -Property y -Minimum).Minimum
    $pair = @($opts | Where-Object { $_.y -le ($minY + 40) })
    if ($pair.Count -lt 2) { return $null }
    $sel = @($pair | Where-Object { $_.checked })
    if ($sel.Count -ne 1) { return $null }
    return [pscustomobject]@{ pair = $pair; selected = $sel[0] }
}

function ReportLang([string]$xml, [string]$tag) {
    $p = ParseLang $xml
    if ($null -eq $p) { Write-Host "$tag => NOT_FOUND"; return "NOT_FOUND" }
    $lang = if ($p.selected.text -eq 'English') { "ENGLISH" } else { "CHINESE" }
    Write-Host "$tag => $lang   (selected y=$($p.selected.y))"
    return $lang
}

for ($i = 0; $i -lt 4; $i++) { & $adb -s $Device shell input keyevent KEYCODE_BACK 2>&1 | Out-Null; Start-Sleep -Milliseconds 400 }
Start-Sleep -Seconds 1
# SettingsSectionActivity 是 exported=false（adb 起不来），所以走导出的主页再点「输入」行
& $adb -s $Device shell "am force-stop com.board.ime" 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Device shell "am start -n com.board.ime/.settings.BoardSettingsActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
$homeXml = DumpXml "home"
$rowXml = $null
foreach ($m in [regex]::Matches($homeXml, '<node[^>]*>')) {
    $v = $m.Value
    $t = [regex]::Match($v, 'text="([^"]*)"').Groups[1].Value
    if ($t -eq '输入') { $rowXml = $v; break }
}
if ($rowXml -ne $null) {
    $b = [regex]::Match($rowXml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    $rx = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)
    $ry = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
    Write-Host "输入行 ($rx,$ry)"
    & $adb -s $Device shell input tap $rx $ry 2>&1 | Out-Null
    Start-Sleep -Seconds 3
} else {
    Write-Host "未找到「输入」行"
}

$xml = DumpXml "mode"
$cur = ReportLang $xml "current"

if ($Set -ne "" -and $cur -ne $Set) {
    $p = ParseLang $xml
    if ($null -ne $p) {
        $target = @($p.pair | Where-Object { ($_.text -eq 'English') -eq ($Set -eq 'ENGLISH') })
        if ($target.Count -ge 1) {
            Write-Host "tap $Set @ ($($target[0].x),$($target[0].y))"
            & $adb -s $Device shell input tap $target[0].x $target[0].y 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            ReportLang (DumpXml "mode2") "after-set" | Out-Null
        }
    }
}
