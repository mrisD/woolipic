@echo off
setlocal EnableDelayedExpansion
title WooliPic Build

rem ==================================================================
rem  WooliPic - build script
rem
rem  Building for Forge 1.21.4 requires THREE JDKs:
rem    JDK 21 - the build JDK (Minecraft 1.21.4 targets Java 21)
rem    JDK 25 - runtime for ForgeGradle 7's internal "mavenizer" tool
rem    JDK 8  - processes Mojang's official mappings
rem  Any missing one is auto-downloaded by Gradle (foojay-resolver).
rem  To force local ones, put your paths into the call :findjdk list below.
rem
rem  NOTE: this file is intentionally pure ASCII. Batch files are parsed
rem  with the console code page, so non-ASCII text here breaks on some
rem  systems. Chinese docs live in the .md file next to it.
rem ==================================================================

cd /d "%~dp0"

echo ==================================================================
echo   WooliPic - build
echo ==================================================================
echo.

rem ---------- 1. locate JDK 21 (the build JDK) ----------
set "JDK21="
call :findjdk JDK21 "E:\PyCharm 2025.1.1\jbr"
call :findjdk JDK21 "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21*"
call :findjdk JDK21 "%ProgramFiles%\Eclipse Adoptium\jdk-21*"
call :findjdk JDK21 "%ProgramFiles%\Java\jdk-21*"
call :findjdk JDK21 "%ProgramFiles%\Microsoft\jdk-21*"
call :findjdk JDK21 "%ProgramFiles%\Amazon Corretto\jdk21*"
if not defined JDK21 if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK21=%JAVA_HOME%"
if not defined JDK21 (
    echo [ERROR] JDK 21 not found.
    echo         Download: https://adoptium.net/temurin/releases/?version=21
    echo         Or add your JDK 21 path to the call :findjdk list in this file.
    echo.
    pause
    exit /b 1
)
echo [1/4] Build JDK 21 : %JDK21%

rem ---------- 2. locate JDK 8 / JDK 25 (used internally by ForgeGradle) ----------
set "JDK8="
set "JDK25="
call :findjdk JDK8 "F:\mctool\work\jdk8\jdk8u504-b01"
call :findjdk JDK8 "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-8*"
call :findjdk JDK8 "%ProgramFiles%\Eclipse Adoptium\jdk-8*"
call :findjdk JDK8 "%ProgramFiles%\Java\jdk1.8*"

call :findjdk JDK25 "F:\mctool\work\jdk25\jdk-25.0.4.1+1"
call :findjdk JDK25 "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25*"
call :findjdk JDK25 "%ProgramFiles%\Eclipse Adoptium\jdk-25*"
call :findjdk JDK25 "%ProgramFiles%\Java\jdk-25*"

set "TOOLCHAINS=%JDK21%"
if defined JDK8 (
    set "TOOLCHAINS=%TOOLCHAINS%,%JDK8%"
    echo [2/4] Found JDK 8  : %JDK8%
) else (
    echo [2/4] JDK 8  not found locally, Gradle will download it
)
if defined JDK25 (
    set "TOOLCHAINS=%TOOLCHAINS%,%JDK25%"
    echo       Found JDK 25 : %JDK25%
) else (
    echo       JDK 25 not found locally, Gradle will download it
)

rem ---------- 3. remove leftovers from a previous failed build ----------
echo [3/4] Cleaning leftovers ...
call :cleanbad "%USERPROFILE%\.gradle"
call :cleanbad "F:\mctool\work\gradle-home"

rem ---------- 4. build ----------
echo [4/4] Building. First run downloads dependencies, could take a while.
echo.
set "JAVA_HOME=%JDK21%"
if defined JDK8 set "JAVA_HOME_8=%JDK8%"
set "PATH=%JDK21%\bin;%PATH%"

rem Use the prepared Gradle home on F:. It already contains the Gradle 9.3.1
rem distribution and everything downloaded so far, which skips a large
rem re-download (services.gradle.org is slow here). If that folder is ever
rem missing, Gradle falls back to %USERPROFILE%\.gradle; delete this line to
rem always use the default location.
if exist "F:\mctool\work\gradle-home" set "GRADLE_USER_HOME=F:\mctool\work\gradle-home"

rem Give the toolchain paths through the Gradle project property. Note the
rem quotes: JDK paths often contain spaces (e.g. "PyCharm 2025.1.1"), and
rem unquoted the shell splits the value into several arguments, which shows
rem up as a bizarre "ClassNotFoundException: 2025.1.1\jbr,..." error.
rem GRADLE_OPTS is deliberately NOT used: it goes through the launcher
rem script's own argument parsing and breaks on paths containing spaces.
call gradlew.bat "-Porg.gradle.java.installations.paths=%TOOLCHAINS%" --no-daemon --console=plain build
set "RESULT=%ERRORLEVEL%"

echo.
if "%RESULT%"=="0" goto ok
goto fail

:ok
echo ==================================================================
echo   BUILD SUCCEEDED
echo ==================================================================
echo.
echo   jar file(s) in build\libs\ :
for %%F in ("build\libs\*.jar") do echo     %%~nxF
echo.
echo   Copy it into your mods folder:
echo     %APPDATA%\.minecraft\mods\
echo   Then start Forge 1.21.4 and type  /woolipic  in game.
echo.
pause
exit /b 0

:fail
echo ==================================================================
echo   BUILD FAILED (exit code %RESULT%)
echo ==================================================================
echo.
echo   Send me everything from "What went wrong" onwards.
echo.
echo   Common causes:
echo     * JDK 8 / JDK 25 missing and the auto-download timed out
echo       -^> add your local JDK paths to the call :findjdk list above
echo     * cannot download Gradle dependencies
echo       -^> retry on another network, or configure a proxy
echo     * method names mismatch this version (SRG naming)
echo       -^> those errors look like: cannot find symbol: method blit(...)
echo.
pause
exit /b %RESULT%

rem ==================================================================
rem  subroutines
rem ==================================================================

rem find a directory containing bin\javac.exe under %2 (wildcards allowed),
rem store it in the variable named by %1
:findjdk
if defined %~1 exit /b 0
for /d %%D in ("%~2") do (
    if not defined %~1 if exist "%%~D\bin\javac.exe" set "%~1=%%~D"
)
if exist "%~2\bin\javac.exe" if not defined %~1 set "%~1=%~2"
exit /b 0

rem delete jars smaller than 100 bytes in the Gradle cache; a failed
rem AccessTransformer run leaves a broken empty jar behind that makes
rem every following build fail with a confusing read-only error
:cleanbad
if not exist "%~1\caches\minecraftforge" exit /b 0
for /f "delims=" %%F in ('dir /b /s "%~1\caches\minecraftforge\*.jar" 2^>nul') do (
    for %%S in ("%%F") do (
        if %%~zS LSS 100 del /f /q "%%F" >nul 2>nul
    )
)
exit /b 0
