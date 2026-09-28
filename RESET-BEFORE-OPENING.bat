@echo off
cd /d "%~dp0"
echo Closing stale build output...
if exist target rmdir /s /q target
if exist .idea rmdir /s /q .idea
for %%F in (*.iml) do del /q "%%F"
echo.
echo CLEANED.
echo Now open THIS folder in IntelliJ, then open pom.xml and choose "Load Maven Project" / "Reload All Maven Projects".
echo The new build uses local port 18080 and automatically stops an older MMcasesBot Java process.
pause
