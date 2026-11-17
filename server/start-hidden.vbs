' Starts the Roadwise server with no console window (used by the Startup folder shortcut).
Set sh = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
sh.Run """" & dir & "\run.cmd""", 0, False
