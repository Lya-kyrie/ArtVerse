Add-Type -AssemblyName System.Windows.Forms
Set-Location "d:\develop\Vibe Coding\frontend"
$proc = Start-Process -FilePath "npx.cmd" -ArgumentList "vite","--port","5173","--host","0.0.0.0" -WorkingDirectory "d:\develop\Vibe Coding\frontend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "d:\develop\Vibe Coding\frontend\vite.log" -RedirectStandardError "d:\develop\Vibe Coding\frontend\vite.err.log"
"Started PID: $($proc.Id)" | Out-File "d:\develop\Vibe Coding\frontend\start-info.log" -Encoding utf8
