# Board Windows Companion build script.
# Uses only the in-box csc.exe - no .NET SDK, no NuGet, no Visual Studio.
# ASCII only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a BOM.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$build = Join-Path $root 'build'

$sw = [System.Diagnostics.Stopwatch]::StartNew()

# 1) locate the in-box C# compiler
$cscCandidates = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
)
$csc = $cscCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $csc) {
    Write-Host "ERROR: in-box csc.exe not found"
    exit 1
}
Write-Host "compiler: $csc"

# 2) clean
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $build | Out-Null

$refs = @(
    '/r:System.dll',
    '/r:System.Core.dll',
    '/r:System.Web.Extensions.dll',
    '/r:System.Security.dll',
    '/r:System.Windows.Forms.dll',
    '/r:System.Drawing.dll'
)
$common = @('/nologo', '/platform:anycpu', '/optimize+')

# 3) main app：**WinExe**（启动不出现控制台黑窗口），后台托盘运行
$mainOut = Join-Path $build 'BoardCompanion.exe'
& $csc '/target:winexe' @common @refs "/win32icon:$root\app.ico" "/out:$mainOut" `
    "$root\Program.cs" `
    "$root\ClipboardHasher.cs" `
    "$root\ClipboardPayload.cs" `
    "$root\ClipboardImage.cs" `
    "$root\SyncClipboardProvider.cs" `
    "$root\WebDavUrlPolicy.cs" `
    "$root\WebDavClient.cs" `
    "$root\CredentialStore.cs" `
    "$root\AutoStart.cs" `
    "$root\ClipboardMonitor.cs" `
    "$root\HotKey.cs" `
    "$root\ClipboardFileCache.cs" `
    "$root\CloudLibrary.cs" `
    "$root\CloudLibraryRemote.cs" `
    "$root\CloudLibraryForm.cs" `
    "$root\ClipboardSyncEngine.cs" `
    "$root\TrayController.cs" `
    "$root\SettingsForm.cs" `
    "$root\StatusForm.cs" `
    "$root\LogForm.cs"
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: main build failed (exit=$LASTEXITCODE)"
    exit 1
}
$size = (Get-Item $mainOut).Length
$sha = (Get-FileHash $mainOut -Algorithm SHA256).Hash.ToLower()
Write-Host "built: $mainOut"
Write-Host ("size: {0:N0} bytes" -f $size)
Write-Host "sha256: $sha"

# 4) tests (reference the main exe instead of recompiling the sources)
$testOut = Join-Path $build 'BoardCompanion.Tests.exe'
& $csc '/target:exe' @common @refs "/r:$mainOut" "/out:$testOut" "$root\Tests\Program.cs"
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: test build failed (exit=$LASTEXITCODE)"
    exit 1
}
Write-Host "built: $testOut"

# 5) run tests
Write-Host ""
& $testOut
$testExit = $LASTEXITCODE
$sw.Stop()
Write-Host ""
Write-Host ("build time: {0:N0} ms" -f $sw.ElapsedMilliseconds)

if ($testExit -ne 0) {
    Write-Host "TESTS FAILED"
    exit $testExit
}
Write-Host "ALL PASS"
exit 0
