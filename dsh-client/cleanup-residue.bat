@echo off
rem ============================================================
rem  residue cleanup: find java / javaw / jcef processes
rem  that still lock this folder.
rem  (English only: cmd misparses UTF-8 batch files)
rem  1) list processes  2) kill them  3) list remaining files
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
echo [i] target folder: %~dp0
echo.

echo [1/3] checking lingering processes ...
set "FOUND="
for /f "tokens=1,2 delims=," %%A in ('tasklist /fo csv /nh /fi "imagename eq javaw.exe"') do (
  echo     javaw.exe        PID=%%~B
  set "FOUND=1"
)
for /f "tokens=1,2 delims=," %%A in ('tasklist /fo csv /nh /fi "imagename eq java.exe"') do (
  echo     java.exe         PID=%%~B
  set "FOUND=1"
)
for /f "tokens=1,2 delims=," %%A in ('tasklist /fo csv /nh /fi "imagename eq jcef_helper.exe"') do (
  echo     jcef_helper.exe  PID=%%~B
  set "FOUND=1"
)

if not defined FOUND (
  echo     none found.
  goto :delcheck
)

echo.
echo [2/3] kill them? (this closes the running client)
choice /c YN /m "      press Y to continue"
if errorlevel 2 goto :end
taskkill /f /im javaw.exe >nul 2>&1
taskkill /f /im java.exe >nul 2>&1
taskkill /f /im jcef_helper.exe >nul 2>&1
timeout /t 2 >nul
echo     done.

:delcheck
echo.
echo [3/3] files still in this folder:
dir /a /b "%~dp0" 2>nul
echo.
echo     If files above still exist, a process still holds them.
echo     Close the client and run this again.
:end
pause
endlocal
