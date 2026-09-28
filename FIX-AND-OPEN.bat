@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title MMcasesBot - Fix and Open

echo ===============================================
echo MARINERS MENTOR MMCASEBOT - ONE TIME FIX
echo ===============================================
echo.

echo [1/4] Stopping OLD Java bot using ports 8080 / 18080...
for %%P in (8080 18080) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr ":%%P " ^| findstr LISTENING 2^>nul') do (
    for /f "tokens=1" %%N in ('tasklist /FI "PID eq %%A" /FO CSV /NH 2^>nul') do (
      set "PNAME=%%~N"
      if /I "!PNAME!"=="java.exe" (
        echo Stopping java.exe PID %%A on port %%P...
        taskkill /PID %%A /F >nul 2>&1
      ) else if /I "!PNAME!"=="javaw.exe" (
        echo Stopping javaw.exe PID %%A on port %%P...
        taskkill /PID %%A /F >nul 2>&1
      ) else (
        echo Port %%P is used by !PNAME! PID %%A - not killed because it is not Java.
      )
    )
  )
)

echo [2/4] Removing stale IntelliJ/build files...
if exist target rmdir /s /q target
if exist .idea rmdir /s /q .idea
for %%F in (*.iml) do del /q "%%F" >nul 2>&1

echo [3/4] Verifying NEW project...
findstr /C:"4.31.0" pom.xml >nul || (
  echo ERROR: This is not the new pom.xml.
  pause
  exit /b 1
)
findstr /C:"server.port=18080" src\main\resources\application.properties >nul || (
  echo ERROR: application.properties does not contain port 18080.
  pause
  exit /b 1
)
echo OK - Selenium 4.31.0 / Port 18080 project detected.

echo [4/4] Opening THIS folder in IntelliJ...
set "IDEA=C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\bin\idea64.exe"
if exist "%IDEA%" (
  start "" "%IDEA%" "%CD%"
  echo IntelliJ opened. When it loads, click Maven Reload if shown, then Run Main.java.
) else (
  echo IntelliJ path was not found automatically.
  echo Open IntelliJ manually and choose THIS exact folder:
  echo %CD%
)

echo.
echo EXPECTED CONSOLE:
echo   Tomcat initialized with port 18080
echo   selenium-java\4.31.0
echo   BUILD = 20260916 NEW PROJECT + PORT 18080 + SID V2 + SPFO
echo.
pause
