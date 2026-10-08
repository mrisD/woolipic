@echo off
setlocal EnableDelayedExpansion
title WooliPic Install

rem ==================================================================
rem  WooliPic - install into the Minecraft instance
rem
rem  This machine runs PCL2 with "version isolation" enabled, which means
rem  each version has its own mods folder. For this setup the mod must go
rem  into:
rem    ...\.minecraft\versions\1.21.4-Forge_54.1.18\mods\
rem  and NOT into .minecraft\mods (that folder is ignored here).
rem
rem  The script finds the built jar, shows where it is going, copies it and
rem  verifies the copy. Pure ASCII on purpose: batch files are parsed with
rem  the console code page.
rem ==================================================================

cd /d "%~dp0"

rem ------------------------------------------------------------------
rem  Locate the Minecraft instance.
rem  The PCL folder name contains non-ASCII characters ("PCL <chinese>"),
rem  and this batch file is deliberately pure ASCII - writing those
rem  characters here would break parsing on some console code pages.
rem  So we match the folder with a wildcard instead of hard-coding it.
rem  If auto-detection fails, set MCROOT manually below.
rem ------------------------------------------------------------------
set "MCROOT="
if exist "F:\EFI\.minecraft\versions" set "MCROOT=F:\EFI\.minecraft"
set "PCLROOT="
if not defined MCROOT (
    for /d %%D in ("F:\EFI\PCL*") do (
        if not defined PCLROOT if exist "%%~D\.minecraft\versions" set "PCLROOT=%%~D\.minecraft"
    )
)
if not defined MCROOT if defined PCLROOT set "MCROOT=%PCLROOT%"

rem Second place: any folder on F: that directly contains a .minecraft dir
if not defined MCROOT (
    for /d %%D in ("F:\*") do (
        if not defined MCROOT if exist "%%~D\.minecraft\versions" set "MCROOT=%%~D\.minecraft"
    )
)

set "VERSION=1.21.4-Forge_54.1.18"
set "MODDIR=%MCROOT%\versions\%VERSION%\mods"

echo ==================================================================
echo   WooliPic - install
echo ==================================================================
echo.

if not defined MCROOT (
    echo [ERROR] Could not locate your .minecraft folder.
    echo         Open this file and set MCROOT manually, e.g.
    echo             set "MCROOT=D:\Games\.minecraft"
    echo.
    pause
    exit /b 1
)
echo [0/3] Minecraft root:
echo         %MCROOT%

rem ---------- 1. find the built jar ----------
set "JAR="
for %%F in ("build\libs\woolipic-*.jar") do set "JAR=%%~fF"
if not defined JAR (
    echo [ERROR] No jar found in build\libs\
    echo.
    echo         Build it first: run build.bat in this folder.
    echo.
    pause
    exit /b 1
)
echo [1/3] Found jar:
echo         %JAR%
for %%F in ("%JAR%") do echo         size: %%~zF bytes

rem ---------- 2. check the target instance ----------
if not exist "%MCROOT%\versions\%VERSION%" (
    echo.
    echo [ERROR] Minecraft version folder not found:
    echo         %MCROOT%\versions\%VERSION%
    echo.
    echo         Edit the MCROOT / VERSION values at the top of this file
    echo         if your game is installed somewhere else.
    echo.
    pause
    exit /b 1
)
if not exist "%MODDIR%" mkdir "%MODDIR%"
echo [2/3] Target mods folder:
echo         %MODDIR%

rem ---------- 3. copy and verify ----------
echo [3/3] Copying ...
copy /y "%JAR%" "%MODDIR%\" >nul
set "DEST=%MODDIR%\"
for %%F in ("%JAR%") do set "DEST=%MODDIR%\%%~nxF"

if not exist "%DEST%" (
    echo.
    echo [ERROR] Copy failed. Is the game running? Close Minecraft and retry.
    echo.
    pause
    exit /b 1
)

for %%A in ("%JAR%") do set "SRCSIZE=%%~zA"
for %%B in ("%DEST%") do set "DSTSIZE=%%~zB"
if not "%SRCSIZE%"=="%DSTSIZE%" (
    echo.
    echo [ERROR] Size mismatch after copy: %SRCSIZE% vs %DSTSIZE%
    echo.
    pause
    exit /b 1
)

echo.
echo ==================================================================
echo   INSTALLED OK
echo ==================================================================
echo.
echo   %DEST%
echo.
echo   Next steps:
echo     1. Start Minecraft with the "1.21.4-Forge_54.1.18" version in PCL2
echo     2. Load any single-player world (cheats on, so commands work)
echo     3. Type  /woolipic  in chat to open the picker
echo     4. Pick an image, adjust size, hit "start placing"
echo.
echo   Note: this mod is client-side. It places wool blocks via /fill
echo   commands, so it needs operator rights - in single player you
echo   already have them.
echo.
echo   Current mods folder contents:
dir /b "%MODDIR%"
echo.
pause
endlocal
