@echo off
rem ============================================================
rem  DSH client launcher (3.0.1+)
rem  - this window only runs checks, then starts the client with javaw
rem    and exits immediately => no lingering cmd window
rem  - debug: set DSH_DEBUG=1 before running to keep the console
rem  - logs: %USERPROFILE%\.dsh-client-startup.log , dsh-client-launch.log
rem  - native crash: hs_err.log next to this file
rem ============================================================
setlocal
cd /d "%~dp0"
set "HOME_DIR=%~dp0"
set "LOG=%~dp0dsh-client-launch.log"
echo ==== %DATE% %TIME% ==== > "%LOG%"

rem ---- locate java / javaw ----
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%J"
if not defined JAVA (
  echo [X] Java not found.
  echo     Install JDK 17+ : https://adoptium.net/temurin/releases/?version=17
  echo     Or set JAVA_HOME to your JDK folder.
  echo [X] java not found >> "%LOG%"
  pause
  exit /b 1
)
set "JAVAW=%JAVA:java.exe=javaw.exe%"
if not exist "%JAVAW%" set "JAVAW="

echo [i] java  : %JAVA%
echo [i] javaw : %JAVAW%
"%JAVA%" -version >> "%LOG%" 2>&1

rem ---- sanity checks ----
if not exist "%~dp0DshClient.jar" (
  echo [X] DshClient.jar not found in this folder.
  echo     Extract the whole zip first; do not copy only the jar.
  echo [X] DshClient.jar missing >> "%LOG%"
  pause
  exit /b 2
)

set "USE_CEF="
if exist "cef\jcef-api-132.3.1.jar" if exist "cef\jcefmaven-132.3.1.jar" set "USE_CEF=1"

set "CRASH=-XX:ErrorFile=%~dp0hs_err.log"

if defined USE_CEF (
  echo [i] mode  : embedded browser (cef found)
  echo [i] cef classpath enabled >> "%LOG%"
) else (
  echo [i] mode  : plain (cef not found)
  echo [i] cef missing, plain mode >> "%LOG%"
)

rem ---- debug mode: stay in console ----
if defined DSH_DEBUG (
  echo [i] DEBUG mode: console stays open
  if defined USE_CEF (
    "%JAVA%" %CRASH% -cp "%HOME_DIR%DshClient.jar;%HOME_DIR%cef\*" dev.example.dshclient.DshClient %*
  ) else (
    "%JAVA%" %CRASH% -jar "%HOME_DIR%DshClient.jar" %*
  )
  echo [exit] code=%ERRORLEVEL% >> "%LOG%"
  pause
  exit /b
)

rem ---- normal mode: detach, close this window right away ----
rem switch out of this folder so the app does not lock it (CWD lock)
cd /d "%USERPROFILE%"
echo [i] starting client in background ...
if defined USE_CEF (
  if defined JAVAW (
    start "" "%JAVAW%" %CRASH% -cp "%HOME_DIR%DshClient.jar;%HOME_DIR%cef\*" dev.example.dshclient.DshClient %*
  ) else (
    start "" "%JAVA%" %CRASH% -cp "%HOME_DIR%DshClient.jar;%HOME_DIR%cef\*" dev.example.dshclient.DshClient %*
  )
) else (
  if defined JAVAW (
    start "" "%JAVAW%" %CRASH% -jar "%HOME_DIR%DshClient.jar" %*
  ) else (
    start "" "%JAVA%" %CRASH% -jar "%HOME_DIR%DshClient.jar" %*
  )
)
echo [run] detached >> "%LOG%"
timeout /t 1 >nul
endlocal
exit
