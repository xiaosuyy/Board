# webdav-lite: minimal local WebDAV for testing Board sync (GET/PUT/MKCOL/HEAD).
# Test only: no auth, no concurrency control.
param(
    [int]$Port = 8080,
    [string]$Root = "E:\github\board\_webdav_remote"
)

$ErrorActionPreference = "Continue"
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
New-Item -ItemType Directory -Force -Path $Root | Out-Null
Write-Host "webdav-lite on http://localhost:$Port/ root=$Root"

while ($listener.IsListening) {
    try { $ctx = $listener.GetContext() } catch { break }
    $req = $ctx.Request
    $res = $ctx.Response
    $rel = [Uri]::UnescapeDataString($req.Url.AbsolutePath).TrimStart('/')
    $path = if ($rel) { Join-Path $Root $rel } else { $Root }
    $method = $req.HttpMethod
    $code = 500
    try {
        switch ($method) {
            "GET" {
                if (Test-Path -LiteralPath $path -PathType Leaf) {
                    $bytes = [System.IO.File]::ReadAllBytes($path)
                    $res.ContentType = "application/octet-stream"
                    $res.StatusCode = 200
                    $res.ContentLength64 = $bytes.Length
                    $res.OutputStream.Write($bytes, 0, $bytes.Length)
                    $code = 200
                } else { $res.StatusCode = 404; $code = 404 }
            }
            "PUT" {
                $dir = Split-Path -Parent $path
                if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
                $fs = [System.IO.File]::Create($path)
                $req.InputStream.CopyTo($fs)
                $fs.Close()
                $res.StatusCode = 201
                $code = 201
            }
            "MKCOL" {
                New-Item -ItemType Directory -Force -Path $path | Out-Null
                $res.StatusCode = 201
                $code = 201
            }
            "HEAD" {
                if (Test-Path -LiteralPath $path) { $res.StatusCode = 200; $code = 200 }
                else { $res.StatusCode = 404; $code = 404 }
            }
            "PROPFIND" { $res.StatusCode = 207; $code = 207 }
            default { $res.StatusCode = 405; $code = 405 }
        }
    } catch {
        try { $res.StatusCode = 500 } catch {}
        Write-Host "ERR $method $rel : $_"
    } finally {
        try { $res.OutputStream.Close() } catch {}
        try { $res.Close() } catch {}
    }
    if ($method -ne "PROPFIND") {
        Write-Host "$method /$rel -> $code"
    }
}
