@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title Mariners Mentor MMcasesBot - READY

echo ==============================================================
echo MARINERS MENTOR MMCASEBOT - BUILD AND RUN THE FILES IN THIS FOLDER
echo ==============================================================
echo Project: %CD%
echo.

rem Use the JDK already installed on this PC. Fall back to JAVA on PATH.
if exist "C:\Users\New\.jdks\openjdk-26.0.2.1\bin\java.exe" (
  set "JAVA_HOME=C:\Users\New\.jdks\openjdk-26.0.2.1"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"

rem Stop only older MMcasesBot Java processes; do not kill unrelated Java programs.
echo [1/4] Stopping any older MMcasesBot process...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$self=$PID; Get-CimInstance Win32_Process -ErrorAction SilentlyContinue ^| Where-Object { ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and $_.ProcessId -ne $self -and $_.CommandLine -match 'org\.example\.Main' -and $_.CommandLine -match 'MMcasesBot' } ^| ForEach-Object { Write-Host ('Stopping old MMcasesBot PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

if exist target rmdir /s /q target

rem Find Maven. Prefer IntelliJ's bundled Maven so no separate Maven install is needed.
set "MVN="
for %%M in (
  "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven\lib\maven3\bin\mvn.cmd"
  "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\plugins\maven\lib\maven3\bin\mvn.cmd"
  "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2\plugins\maven\lib\maven3\bin\mvn.cmd"
) do if not defined MVN if exist %%~M set "MVN=%%~M"

if not defined MVN (
  for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MVN set "MVN=%%M"
)

if not defined MVN (
  echo ERROR: Maven was not found.
  echo Open this folder in IntelliJ, open pom.xml, click Load Maven Project, then run Main.java.
  pause
  exit /b 1
)

echo [2/4] Maven: %MVN%
echo [3/4] CLEAN BUILD - this ignores old IntelliJ target/classes and old cached project output...
set "MAVEN_OPTS=-Xmx768m -XX:MaxMetaspaceSize=256m"
call "%MVN%" -DskipTests clean package
if errorlevel 1 (
  echo.
  echo BUILD FAILED. Copy the RED ERROR lines from above and send them to ChatGPT.
  pause
  exit /b 1
)

if not exist "target\MMcasesBot-ready.jar" (
  echo ERROR: target\MMcasesBot-ready.jar was not created.
  pause
  exit /b 1
)

echo.
echo [4/4] STARTING BOT...
echo EXPECTED: LOCAL MODE ^| NO TOMCAT / NO PORT
echo EXPECTED: BUILD = 20260916 OPEN-SPFO-JSU + MEMBERSHIP-COMPACT
echo.
"%JAVA_CMD%" -Xms128m -Xmx1024m -Dfile.encoding=UTF-8 -jar "target\MMcasesBot-ready.jar"

echo.
echo MMcasesBot stopped. Exit code: %ERRORLEVEL%
pause
