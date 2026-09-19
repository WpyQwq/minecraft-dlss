@echo off
REM ---------------------------------------------------------------------------
REM Build the native bridge DLL (wpywdlss_bridge.dll).
REM
REM Sets up the MSVC x64 environment itself, so this can be run from a plain
REM cmd or PowerShell. Requires CMake and Ninja on PATH.
REM
REM Avoids parenthesised if-blocks on purpose: the VS path contains "(x86)",
REM and expanding such a value inside a ( ) block truncates it.
REM ---------------------------------------------------------------------------
setlocal EnableExtensions

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "HERE=%~dp0"
set "NATIVE=%HERE%.."
set "BUILD=%NATIVE%\build"

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.10"

if not exist "%VCVARS%" goto :err_vcvars
if not exist "%JAVA_HOME%\include\jni.h" goto :err_javahome

echo [1/4] entering MSVC x64 environment
call "%VCVARS%" >nul 2>&1
if errorlevel 1 goto :err_vcvars

echo [2/4] ensuring vulkan-1.lib exists
if not exist "%NATIVE%\third_party\vulkan\lib\vulkan-1.lib" call "%HERE%make_vulkan_lib.cmd"
if not exist "%NATIVE%\third_party\vulkan\lib\vulkan-1.lib" goto :err_vulkanlib

echo [3/4] configuring (cmake -G Ninja)
cmake -S "%NATIVE%" -B "%BUILD%" -G Ninja -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="%JAVA_HOME%"
if errorlevel 1 goto :err_cmake

echo [4/4] building
cmake --build "%BUILD%"
if errorlevel 1 goto :err_build

echo.
echo [OK] built:
dir /b "%BUILD%\wpywdlss_bridge.dll" 2>nul
exit /b 0

:err_vcvars
echo [ERROR] vcvars64.bat missing or failed: %VCVARS%
exit /b 1

:err_javahome
echo [ERROR] jni.h not found under JAVA_HOME=%JAVA_HOME%
exit /b 1

:err_vulkanlib
echo [ERROR] could not produce vulkan-1.lib
exit /b 1

:err_cmake
echo [ERROR] cmake configure failed
exit /b 1

:err_build
echo [ERROR] cmake build failed
exit /b 1
