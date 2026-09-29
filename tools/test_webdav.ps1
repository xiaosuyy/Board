# Board 开发用：最小 WebDAV 服务器（只用于本机联调 SyncClipboard 协议）
#
# 支持：GET / PUT / MKCOL / PROPFIND / DELETE + Basic 认证
# 用途：给模拟器一个真实的 WebDAV 端点（模拟器里用 http://10.0.2.2:<Port>/dav 访问）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/test_webdav.ps1 -Port 8080 -User board -Password secret
#
# 说明：这是**测试工具**，不随 APK 发布；没有做完整的 RFC 4918（锁、深度 PROPFIND 等）。

param(
    [int]$Port = 8080,
    [string]$Root = "",
    [string]$User = "board",
    [string]$Password = "secret"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Join-Path $env:TEMP "board_webdav_root"
}
if (-not (Test-Path $Root)) {
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
}
$Root = (Resolve-Path $Root).Path

$prefix = "http://localhost:$Port/"
$listener = New-Object System.Net.HttpListener
# 先试通配前缀（设备用 10.0.2.2:<port> 访问时 Host 头不是 localhost，
# 不绑通配前缀 HttpListener 会直接回 400）；没有管理员权限时退回 localhost。
$wildcardOk = $false
try {
    $listener.Prefixes.Add("http://+:$Port/")
    $wildcardOk = $true
} catch {
    $listener.Prefixes.Add($prefix)
}
$listener.Start()

Write-Host "WebDAV 测试服务器已启动"
Write-Host "  监听      : $(if ($wildcardOk) { "http://+:$Port/ (通配)" } else { $prefix })  (模拟器访问 http://10.0.2.2:$Port/dav)"
Write-Host "  根目录    : $Root"
Write-Host "  认证      : $User / $Password"
Write-Host "  按 Ctrl+C 停止"

function Write-DavLog([string]$message) {
    $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $message
    Add-Content -Path (Join-Path $script:Root "server.log") -Value $line -Encoding UTF8
}

function Test-Auth($ctx) {
    # 开发用：-User none 表示关闭鉴权（跑测试更省事）
    if ($script:User -eq "none" -or ($script:User -eq "" -and $script:Password -eq "")) {
        return $true
    }
    $header = $ctx.Request.Headers["Authorization"]
    if ([string]::IsNullOrEmpty($header)) {
        Write-DavLog "auth: 缺少 Authorization 头"
        return $false
    }
    if (-not $header.StartsWith("Basic ")) {
        Write-DavLog "auth: 不是 Basic [$header]"
        return $false
    }
    $decoded = [System.Text.Encoding]::UTF8.GetString(
        [System.Convert]::FromBase64String($header.Substring(6)))
    $expected = "$($script:User):$($script:Password)"
    Write-DavLog "auth: got [$decoded] expected [$expected]"
    return ($decoded -ceq $expected)
}

function Send-Status($ctx, [int]$code, [string]$text = "") {
    $ctx.Response.StatusCode = $code
    if ($text.Length -gt 0) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
        $ctx.Response.ContentType = "text/plain; charset=utf-8"
        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    }
    $ctx.Response.Close()
}

function Get-RelativePath($ctx) {
    $path = [System.Uri]::UnescapeDataString($ctx.Request.Url.AbsolutePath)
    if (-not $path.StartsWith("/dav")) { return $null }
    $rel = $path.Substring(4).TrimStart('/')
    return $rel
}

while ($listener.IsListening) {
    try {
        $ctx = $listener.GetContext()
    } catch {
        break
    }

    try {
        $method = $ctx.Request.HttpMethod.ToUpperInvariant()

        if (-not (Test-Auth $ctx)) {
            $ctx.Response.AddHeader("WWW-Authenticate", "Basic realm=`"Board WebDAV`"")
            Send-Status $ctx 401 "unauthorized"
            Write-Host "[401] $method $($ctx.Request.Url.AbsolutePath)"
            continue
        }

        $rel = Get-RelativePath $ctx
        if ($null -eq $rel) {
            Send-Status $ctx 404 "not found"
            continue
        }
        $target = Join-Path $Root $rel

        switch ($method) {
            "OPTIONS" {
                $ctx.Response.AddHeader("DAV", "1,2")
                $ctx.Response.AddHeader("Allow", "GET, PUT, MKCOL, PROPFIND, DELETE, OPTIONS")
                Send-Status $ctx 200 ""
                Write-Host "[200] OPTIONS $rel"
            }

            "GET" {
                if (Test-Path $target -PathType Leaf) {
                    $bytes = [System.IO.File]::ReadAllBytes($target)
                    $ctx.Response.StatusCode = 200
                    $ctx.Response.ContentType = "application/json; charset=utf-8"
                    $ctx.Response.AddHeader("Last-Modified",
                        (Get-Item $target).LastWriteTimeUtc.ToString("R"))
                    $ctx.Response.ContentLength64 = $bytes.Length
                    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                    $ctx.Response.Close()
                    Write-Host "[200] GET $rel ($($bytes.Length) bytes)"
                } else {
                    Send-Status $ctx 404 "not found"
                    Write-Host "[404] GET $rel"
                }
            }

            "PUT" {
                $parent = Split-Path $target -Parent
                if (-not (Test-Path $parent)) {
                    New-Item -ItemType Directory -Force -Path $parent | Out-Null
                }
                $ms = New-Object System.IO.MemoryStream
                $ctx.Request.InputStream.CopyTo($ms)
                $bytes = $ms.ToArray()
                [System.IO.File]::WriteAllBytes($target, $bytes)
                $ctx.Response.AddHeader("Last-Modified",
                    (Get-Item $target).LastWriteTimeUtc.ToString("R"))
                Send-Status $ctx 201 "created"
                Write-Host "[201] PUT $rel ($($bytes.Length) bytes)"
            }

            "MKCOL" {
                if (Test-Path $target) {
                    Send-Status $ctx 405 "already exists"
                    Write-Host "[405] MKCOL $rel (已存在)"
                } else {
                    New-Item -ItemType Directory -Force -Path $target | Out-Null
                    Send-Status $ctx 201 "created"
                    Write-Host "[201] MKCOL $rel"
                }
            }

            "PROPFIND" {
                if (-not (Test-Path $target)) {
                    Send-Status $ctx 404 "not found"
                    Write-Host "[404] PROPFIND $rel"
                    continue
                }
                $items = @()
                if (Test-Path $target -PathType Container) {
                    $items += Get-ChildItem $target
                } else {
                    $items += Get-Item $target
                }
                $sb = New-Object System.Text.StringBuilder
                [void]$sb.Append('<?xml version="1.0" encoding="utf-8"?>')
                [void]$sb.Append('<D:multistatus xmlns:D="DAV:">')
                foreach ($item in $items) {
                    [void]$sb.Append('<D:response><D:href>/dav/')
                    [void]$sb.Append($item.Name)
                    [void]$sb.Append('</D:href><D:propstat><D:prop><D:getcontentlength>')
                    [void]$sb.Append($item.Length)
                    [void]$sb.Append('</D:getcontentlength><D:getlastmodified>')
                    [void]$sb.Append($item.LastWriteTimeUtc.ToString("R"))
                    [void]$sb.Append('</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>')
                }
                [void]$sb.Append('</D:multistatus>')
                $body = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
                $ctx.Response.StatusCode = 207
                $ctx.Response.ContentType = "application/xml; charset=utf-8"
                $ctx.Response.ContentLength64 = $body.Length
                $ctx.Response.OutputStream.Write($body, 0, $body.Length)
                $ctx.Response.Close()
                Write-Host "[207] PROPFIND $rel ($($items.Count) 项)"
            }

            "DELETE" {
                if (Test-Path $target) {
                    Remove-Item -Recurse -Force $target
                    Send-Status $ctx 204 ""
                    Write-Host "[204] DELETE $rel"
                } else {
                    Send-Status $ctx 404 "not found"
                    Write-Host "[404] DELETE $rel"
                }
            }

            default {
                Send-Status $ctx 405 "method not allowed"
                Write-Host "[405] $method $rel"
            }
        }
    } catch {
        Write-Host "[500] $($_.Exception.Message)"
        try { Send-Status $ctx 500 "server error" } catch { }
    }
}

$listener.Stop()
