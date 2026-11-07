@echo off
rem debug launcher: keeps the console so [term] key / startup logs are visible
setlocal
set DSH_DEBUG=1
set JAVA_TOOL_OPTIONS=-Ddsh.term.debug=1
call "%~dp0run-cef.bat" %*
echo.
echo ==== debug run finished, press any key ====
pause >nul
endlocal
