param([string]$Device = "3B162E000FF00000")
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "E:\OpenCode\blueboard"

function Dump([string]$name) {
    & $adb -s $Device shell uiautomator dump "/sdcard/$name.xml" 2>&1 | Out-Null
    & $adb -s $Device pull "/sdcard/$name.xml" "$root\_probe\$name.xml" 2>&1 | Out-Null
    return [IO.File]::ReadAllText("$root\_probe\$name.xml", [Text.Encoding]::UTF8)
}

# 判定：文本为空或全部是掩码字符 => masked；否则 revealed（不回显具体内容）
function Classify([string]$text) {
    if ([string]::IsNullOrEmpty($text)) { return "masked(empty)" }
    $stripped = $text -replace '[\u2022\u00b7\*\.]', ''
    if ($stripped.Length -eq 0) { return "masked(dots)" }
    return "revealed(len=$($text.Length))"
}

function FindPasswordNode([string]$xml) {
    $nodes = [regex]::Matches($xml, '<node[^>]*>')
    foreach ($n in $nodes) {
        $v = $n.Value
        if ($v -match 'class="android.widget.EditText"' -and $v -match 'password="true"') { return $v }
    }
    return $null
}

Write-Host "== 打开同步设置页 =="
& $adb -s $Device shell "am start -n com.board.ime/.settings.BoardSettingsActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4
& $adb -s $Device shell input tap 600 2208 2>&1 | Out-Null
Start-Sleep -Seconds 4

$xml = Dump "pw1"
$node = FindPasswordNode $xml
if ($null -eq $node) {
    # 页面可能仍在首页，滚动后再进同步
    & $adb -s $Device shell input swipe 608 2200 608 700 300 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $Device shell input tap 600 2208 2>&1 | Out-Null
    Start-Sleep -Seconds 4
    $xml = Dump "pw1"
    $node = FindPasswordNode $xml
}
if ($null -eq $node) { Write-Host "RESULT password_field=NOT_FOUND"; exit 1 }

$t0 = [regex]::Match($node, 'text="([^"]*)"').Groups[1].Value
Write-Host "1) 打开页面默认: $(Classify $t0)"

# 找显示/隐藏按钮（Material 的 end icon，content-desc 含 显示/隐藏 或 show/hide）
$eye = $null
foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
    $v = $m.Value
    $d = [regex]::Match($v, 'content-desc="([^"]*)"').Groups[1].Value
    if ($d -match '显示|隐藏|password|show|hide') { $eye = $v; break }
}
if ($null -eq $eye) { Write-Host "RESULT eye_button=NOT_FOUND" } else {
    $b = [regex]::Match($eye, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    $ex = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)
    $ey = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
    Write-Host "   显示/隐藏按钮 @ ($ex,$ey)"

    & $adb -s $Device shell input tap $ex $ey 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    $xml2 = Dump "pw2"
    $n2 = FindPasswordNode $xml2
    $t1 = if ($n2) { [regex]::Match($n2, 'text="([^"]*)"').Groups[1].Value } else { "" }
    Write-Host "2) 点一次: $(Classify $t1)"

    & $adb -s $Device shell input tap $ex $ey 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    $xml3 = Dump "pw3"
    $n3 = FindPasswordNode $xml3
    $t2 = if ($n3) { [regex]::Match($n3, 'text="([^"]*)"').Groups[1].Value } else { "" }
    Write-Host "3) 再点一次: $(Classify $t2)"
}
Write-Host "done"
