@echo off
rem ============================================================
rem  DSH client launcher - dumb on purpose (ASCII + CRLF only).
rem  No checks here: the jar itself decides everything (route, speed,
rem  fallbacks). Java missing? You will see the java error below.
rem
rem  Optional flags still work, e.g.:
rem    run.bat --route web        (stick to the browser tunnel; remembered)
rem    run.bat --route auto       (back to automatic)
rem    run.bat --route tunnel        (domain/CF tunnel first)
rem    run.bat --route direct        (DEPRECATED, debug only; not used by auto)
rem    run.bat --speedtest        (measure every route, then exit)
rem    run.bat --probe            (loss/RTT judgement only, no connection)
rem ============================================================
cd /d "%~dp0"
rem  NOTE: no forced --route here. The jar defaults to auto, and any route
rem  you set once (run.bat --route web) is remembered in
rem  %USERPROFILE%\.dsh-client.properties and used from then on.
java -jar "DshClient.jar" %*
pause
