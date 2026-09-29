# Board —— 拉取 librime 预编译静态库与标准 Rime 数据
#
# 用法（在项目根目录执行）：
#   powershell -ExecutionPolicy Bypass -File tools\fetch_rime.ps1
#   powershell -ExecutionPolicy Bypass -File tools\fetch_rime.ps1 -Abi arm64-v8a,x86_64
#   powershell -ExecutionPolicy Bypass -File tools\fetch_rime.ps1 -SkipData
#   powershell -ExecutionPolicy Bypass -File tools\fetch_rime.ps1 -SkipLibs
#
# 产出：
#   third_party/rime/<abi>/include/rime_api.h     librime 公共 C API 头文件
#   third_party/rime/<abi>/lib/*.a                librime 及其依赖的静态库
#   android/app/src/main/assets/rime/**           标准 Rime 数据（配置 + 词库 + OpenCC 数据）
#
# 静态库来自 https://github.com/fcitx5-android/prebuilt ，内容是 BSD-3-Clause 的
# librime 及其依赖库的官方源码编译产物。本脚本只下载，不修改任何二进制。

param(
    [string[]]$Abi = @("arm64-v8a", "armeabi-v7a", "x86_64"),
    [switch]$SkipLibs,
    [switch]$SkipData
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LibRoot = Join-Path $ProjectRoot "third_party\rime"
$RimeAssets = Join-Path $ProjectRoot "android\app\src\main\assets\rime"
$TempRoot = Join-Path $env:TEMP "board-rime-fetch"

$PrebuiltBase = "https://raw.githubusercontent.com/fcitx5-android/prebuilt/master"

# ---------------------------------------------------------------- 工具函数

function Get-RemoteFile {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination,
        [int]$Retries = 5,
        [switch]$Optional
    )
    if ((Test-Path $Destination) -and ((Get-Item $Destination).Length -gt 0)) {
        Write-Host ("    [skip] " + (Split-Path -Leaf $Destination))
        return $true
    }
    $dir = Split-Path -Parent $Destination
    if ($dir -and (-not (Test-Path $dir))) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Url -OutFile $Destination -TimeoutSec 900 -UseBasicParsing
            $size = (Get-Item $Destination).Length
            Write-Host ("    [ok]   " + (Split-Path -Leaf $Destination) + " (" + $size + " bytes)")
            return $true
        }
        catch {
            if ($attempt -eq $Retries) {
                if ($Optional) {
                    Write-Host ("    [warn] optional file failed: " + (Split-Path -Leaf $Destination))
                    return $false
                }
                throw ("download failed: " + $Url + " -> " + $_.Exception.Message)
            }
            Write-Host ("    [retry " + $attempt + "/" + $Retries + "] " + (Split-Path -Leaf $Destination))
            Start-Sleep -Seconds (3 * $attempt)
        }
    }
    return $false
}

function Expand-Tarball {
    param([string]$Archive, [string]$Destination)
    if (Test-Path $Destination) { Remove-Item -Recurse -Force $Destination }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    tar -xzf $Archive -C $Destination
    if ($LASTEXITCODE -ne 0) { throw ("extract failed: " + $Archive) }
}

# ---------------------------------------------------------------- 1. 预编译静态库

# 每项格式："远程相对路径模板|本地相对路径"
$LibFiles = @(
    "librime/{0}/include/rime_api.h|include/rime_api.h",
    "librime/{0}/include/rime_levers_api.h|include/rime_levers_api.h",
    "librime/{0}/lib/librime.a|lib/librime.a",
    "opencc/{0}/lib/libopencc.a|lib/libopencc.a",
    "leveldb/{0}/lib/libleveldb.a|lib/libleveldb.a",
    "marisa/{0}/lib/libmarisa.a|lib/libmarisa.a",
    "yaml-cpp/{0}/lib/libyaml-cpp.a|lib/libyaml-cpp.a",
    "glog/{0}/lib/libglog.a|lib/libglog.a"
)

if (-not $SkipLibs) {
    foreach ($arch in $Abi) {
        Write-Host ("== prebuilt libs: " + $arch + " ==")
        foreach ($entry in $LibFiles) {
            $parts = $entry -split '\|'
            $relative = $parts[0].Replace("{0}", $arch)
            $relDest = $parts[1]
            $url = $PrebuiltBase + "/" + $relative
            $dest = [System.IO.Path]::Combine($LibRoot, $arch, $relDest)
            Get-RemoteFile -Url $url -Destination $dest | Out-Null
        }
    }
}

# ---------------------------------------------------------------- 2. OpenCC 数据

# t2s.json 必须和 *.ocd2 放在同一个目录：librime 把相对的 opencc_config 解析到
# <shared_data_dir>/opencc/ 或 <user_data_dir>/opencc/，而 OpenCC 用
# 「配置文件所在目录」去解析 *.ocd2 的相对路径。
$OpenccData = @(
    "t2s.json",
    "t2hk.json",
    "t2jp.json",
    "t2tw.json",
    "s2hk.json",
    "s2t.json",
    "s2tw.json",
    "s2twp.json",
    "hk2s.json",
    "hk2t.json",
    "jp2t.json",
    "tw2s.json",
    "tw2sp.json",
    "tw2t.json",
    "TSCharacters.ocd2",
    "TSPhrases.ocd2",
    "STCharacters.ocd2",
    "STPhrases.ocd2",
    "TWVariants.ocd2",
    "TWVariantsRev.ocd2",
    "TWVariantsRevPhrases.ocd2",
    "TWPhrases.ocd2",
    "TWPhrasesRev.ocd2",
    "HKVariants.ocd2",
    "HKVariantsRev.ocd2",
    "HKVariantsRevPhrases.ocd2",
    "JPVariants.ocd2",
    "JPVariantsRev.ocd2",
    "JPShinjitaiCharacters.ocd2",
    "JPShinjitaiPhrases.ocd2"
)

# ---------------------------------------------------------------- 3. Rime 标准数据

$PreludeFiles = @("default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml")
$LunaPinyinFiles = @("luna_pinyin.schema.yaml", "luna_pinyin_simp.schema.yaml", "luna_pinyin.dict.yaml", "pinyin.yaml", "LICENSE")
$EssayFiles = @("essay.txt", "LICENSE")
# luna_pinyin.schema.yaml 里声明了 dependencies: [stroke] 并用作反查（` 前缀），
# 所以必须一起打包，否则部署会报缺少依赖。stroke 只作为依赖存在，不进入 schema_list。
$StrokeFiles = @("stroke.schema.yaml", "stroke.dict.yaml", "LICENSE")

if (-not $SkipData) {
    if (-not (Test-Path $TempRoot)) { New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null }

    Write-Host "== OpenCC data =="
    foreach ($name in $OpenccData) {
        $url = $PrebuiltBase + "/opencc/data/" + $name
        $dest = [System.IO.Path]::Combine($RimeAssets, "opencc", $name)
        Get-RemoteFile -Url $url -Destination $dest -Optional | Out-Null
    }

    Write-Host "== Rime standard data =="
    $repos = @("rime/rime-prelude", "rime/rime-luna-pinyin", "rime/rime-essay", "rime/rime-stroke")
    foreach ($repo in $repos) {
        $flat = $repo.Replace("/", "-")
        $archive = [System.IO.Path]::Combine($TempRoot, ($flat + ".tar.gz"))
        $url = "https://codeload.github.com/" + $repo + "/tar.gz/refs/heads/master"
        Get-RemoteFile -Url $url -Destination $archive | Out-Null

        $extractDir = [System.IO.Path]::Combine($TempRoot, $flat)
        Expand-Tarball -Archive $archive -Destination $extractDir
        $srcRoot = (Get-ChildItem $extractDir -Directory | Select-Object -First 1).FullName

        $keep = @()
        if ($repo -eq "rime/rime-prelude") { $keep = $PreludeFiles }
        if ($repo -eq "rime/rime-luna-pinyin") { $keep = $LunaPinyinFiles }
        if ($repo -eq "rime/rime-essay") { $keep = $EssayFiles }
        if ($repo -eq "rime/rime-stroke") { $keep = $StrokeFiles }

        foreach ($file in $keep) {
            $src = [System.IO.Path]::Combine($srcRoot, $file)
            if (-not (Test-Path $src)) {
                Write-Host ("    [warn] missing in repo: " + $repo + "/" + $file)
                continue
            }
            $dest = [System.IO.Path]::Combine($RimeAssets, $file)
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
            Copy-Item $src $dest -Force
            Write-Host ("    [ok]   " + $file)
        }
    }
    Remove-Item -Recurse -Force $TempRoot -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "done."
Write-Host ("  libs : " + $LibRoot)
Write-Host ("  data : " + $RimeAssets)
