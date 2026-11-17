# Stops the Roadwise server (the restart loop and node).
Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -match 'Roadwise\\server\\(run\.cmd|server\.js)' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
"Roadwise server stopped."
