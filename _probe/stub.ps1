$ErrorActionPreference = "Stop"
$root = "E:\github\board\_probe"
$file = Join-Path $root "cloud\SyncClipboard.json"
$log  = Join-Path $root "stub.log"
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://127.0.0.1:18080/")
$listener.Start()
Add-Content $log "stub started"
while ($true) {
  $ctx = $listener.GetContext()
  $req = $ctx.Request
  $res = $ctx.Response
  $body = ""
  $path = $req.Url.AbsolutePath
  $auth = $req.Headers["Authorization"]
  if ([string]::IsNullOrEmpty($auth)) {
    $res.StatusCode = 401
    Add-Content $log ("401 " + $req.HttpMethod + " " + $path + " auth=no")
  } elseif ($req.HttpMethod -eq "PUT") {
    $reader = New-Object System.IO.StreamReader($req.InputStream)
    $body = $reader.ReadToEnd()
    [System.IO.File]::WriteAllText($file, $body, (New-Object System.Text.UTF8Encoding($false)))
    $res.StatusCode = 204
    Add-Content $log ("PUT " + $path + " bytes=" + $body.Length + " auth=yes")
  } elseif ($req.HttpMethod -eq "GET") {
    if (Test-Path $file) {
      $body = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
      $res.StatusCode = 200
    } else { $res.StatusCode = 404 }
    Add-Content $log ("GET " + $path + " bytes=" + $body.Length + " auth=yes")
  } elseif ($req.HttpMethod -eq "MKCOL") {
    $res.StatusCode = 201
    Add-Content $log ("MKCOL " + $path + " auth=yes")
  } else {
    $res.StatusCode = 405
  }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
  $res.ContentLength64 = $bytes.Length
  $res.OutputStream.Write($bytes, 0, $bytes.Length)
  $res.Close()
}
