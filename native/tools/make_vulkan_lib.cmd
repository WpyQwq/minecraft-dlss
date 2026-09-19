@echo off
REM ---------------------------------------------------------------------------
REM Synthesise vulkan-1.lib from the system vulkan-1.dll.
REM
REM This removes the need to install the LunarG Vulkan SDK (~1 GB) just to get
REM an import library: Windows already ships vulkan-1.dll, and we only need the
REM matching .lib so the MSVC linker can resolve vkCreateInstance & friends.
REM
REM Run from a *plain* cmd/PowerShell: this script sets up the MSVC environment
REM itself via vcvars64.bat.
REM
REM NOTE: deliberately avoids parenthesised if-blocks and avoids
REM `setlocal EnableDelayedExpansion`, because this script's own paths contain
REM "(x86)" -- expanding such a value inside a ( ) block truncates the block
REM and yields "\Microsoft was unexpected at this time."
REM ---------------------------------------------------------------------------
setlocal EnableExtensions

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "SYS_DLL=%SystemRoot%\System32\vulkan-1.dll"
set "HERE=%~dp0"
set "OUTDIR=%HERE%..\third_party\vulkan\lib"
set "SCRATCH=%HERE%..\build\scratch"
set "TMPDEF=%SCRATCH%\vulkan-1.def"
set "TMPEXP=%SCRATCH%\vulkan_exports.txt"

if not exist "%VCVARS%" goto :err_vcvars
if not exist "%SYS_DLL%" goto :err_dll

if not exist "%SCRATCH%" mkdir "%SCRATCH%"

echo [1/4] entering MSVC x64 environment
call "%VCVARS%" >nul 2>&1
if errorlevel 1 goto :err_vcvars

echo [2/4] dumping exports from the system vulkan-1.dll
dumpbin /nologo /exports "%SYS_DLL%" > "%TMPEXP%"
if errorlevel 1 goto :err_dumpbin

echo [3/4] writing module definition file
> "%TMPDEF%" echo LIBRARY vulkan-1.dll
>> "%TMPDEF%" echo EXPORTS
for /f "usebackq tokens=1,2,3,4" %%a in ("%TMPEXP%") do call :emit "%%d"

if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo [4/4] generating import library
lib /nologo /def:"%TMPDEF%" /machine:x64 /out:"%OUTDIR%\vulkan-1.lib"
if errorlevel 1 goto :err_lib

echo.
echo [OK] wrote %OUTDIR%\vulkan-1.lib
exit /b 0

REM ---- helper: append a symbol to the .def only if it looks like a Vulkan entry
:emit
echo %~1 | findstr /b /r "vk" >nul
if errorlevel 1 exit /b 0
>> "%TMPDEF%" echo %~1
exit /b 0

:err_vcvars
echo [ERROR] vcvars64.bat missing or failed: %VCVARS%
exit /b 1

:err_dll
echo [ERROR] vulkan-1.dll not found: %SYS_DLL%
exit /b 1

:err_dumpbin
echo [ERROR] dumpbin failed
exit /b 1

:err_lib
echo [ERROR] lib failed
exit /b 1
