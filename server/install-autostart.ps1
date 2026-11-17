# Makes the Roadwise server start (hidden) every time you sign in to Windows, and starts it now.
$startup = [Environment]::GetFolderPath('Startup')
$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut("$startup\Roadwise Server.lnk")
$lnk.TargetPath = "$env:WINDIR\System32\wscript.exe"
$lnk.Arguments = "`"$PSScriptRoot\start-hidden.vbs`""
$lnk.WorkingDirectory = $PSScriptRoot
$lnk.Description = 'Roadwise reports + live location server'
$lnk.Save()
& "$PSScriptRoot\stop.ps1" | Out-Null
Start-Process wscript.exe -ArgumentList "`"$PSScriptRoot\start-hidden.vbs`""
"Roadwise server will now start automatically when you sign in. (Remove: delete $startup\Roadwise Server.lnk)"
