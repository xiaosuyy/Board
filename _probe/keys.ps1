Add-Type -AssemblyName System.Drawing
$path = if ($args.Count -ge 1) { $args[0] } else { "E:\OpenCode\blueboard\_probe\s1.png" }
$minRun = if ($args.Count -ge 2) { [int]$args[1] } else { 60 }
$bmp = [System.Drawing.Bitmap]::FromFile($path)
$w = $bmp.Width; $h = $bmp.Height
$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bmp.UnlockBits($data)
Write-Output "size ${w}x${h}"

$bands = @()
$cur = $null
for ($y = 1200; $y -lt $h; $y += 2) {
  $c = 0
  for ($x = 0; $x -lt $w; $x += 2) {
    $i = $y * $stride + $x * 4
    if ($bytes[$i] -ge 246 -and $bytes[$i + 1] -ge 246 -and $bytes[$i + 2] -ge 246) { $c++ }
  }
  if ($c -ge 200) {
    if ($cur -eq $null) { $cur = @{ start = $y; end = $y } } else { $cur.end = $y }
  } else {
    if ($cur -ne $null) { $bands += $cur; $cur = $null }
  }
}
if ($cur -ne $null) { $bands += $cur }

foreach ($b in $bands) {
  if (($b.end - $b.start) -lt 30) { continue }
  $white = New-Object bool[] $w
  for ($y = $b.start; $y -le $b.end; $y += 2) {
    for ($x = 0; $x -lt $w; $x++) {
      if ($white[$x]) { continue }
      $i = $y * $stride + $x * 4
      if ($bytes[$i] -ge 246 -and $bytes[$i + 1] -ge 246 -and $bytes[$i + 2] -ge 246) { $white[$x] = $true }
    }
  }
  $runs = @(); $s = -1
  for ($x = 0; $x -lt $w; $x++) {
    if ($white[$x]) { if ($s -lt 0) { $s = $x } }
    else { if ($s -ge 0) { if (($x - $s) -ge $minRun) { $runs += [pscustomobject]@{ a = $s; b = $x - 1 } }; $s = -1 } }
  }
  if ($s -ge 0 -and ($w - $s) -ge $minRun) { $runs += [pscustomobject]@{ a = $s; b = $w - 1 } }
  $ymid = [int](($b.start + $b.end) / 2)
  $centers = ($runs | ForEach-Object { [int](($_.a + $_.b) / 2) }) -join ","
  Write-Output ("y={0} (band {1}..{2}) 鎸夐敭鏁?{3} 閿績x={4}" -f $ymid, $b.start, $b.end, $runs.Count, $centers)
}

