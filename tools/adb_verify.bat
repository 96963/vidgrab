@echo off
REM VidGrab 快速 ADB 验证脚本 (Windows 批处理版)
REM 无需 Python/Bash，直接用 adb 命令验证关键功能

set PACKAGE=com.vidgrab.app
set DOWNLOAD_DIR=/sdcard/Download/VidGrab
set PRIVATE_DIR=/data/data/%PACKAGE%/files/VidGrab

echo ========================================
echo   VidGrab 快速 ADB 验证 (Windows)
echo ========================================
echo.

REM 检查 adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERR] 未找到 adb，请将 Android SDK platform-tools 加入 PATH
    pause
    exit /b 1
)

echo [INFO] 检查设备连接...
adb devices | findstr /R "device$" >nul
if %errorlevel% neq 0 (
    echo [ERR] 未检测到设备，请开启 USB 调试
    pause
    exit /b 1
)
echo [OK] 设备已连接
echo.

echo [INFO] 检查应用安装状态...
adb shell pm list packages | findstr "^package:%PACKAGE%$" >nul
if %errorlevel% neq 0 (
    echo [ERR] 应用未安装
    pause
    exit /b 1
)
echo [OK] 应用已安装: %PACKAGE%
echo.

echo [INFO] 检查 Native 库 (ABI 支持)...
for /f "delims=" %%a in ('adb shell getprop ro.product.cpu.abi') do set ABI=%%a
echo 设备 ABI: %ABI%
adb shell ls /data/app/%PACKAGE%*/lib/ 2>nul
echo.

echo [INFO] 检查下载目录...
echo --- 公共目录 (%DOWNLOAD_DIR%) ---
adb shell ls -la "%DOWNLOAD_DIR%" 2>nul || echo [WARN] 公共目录不存在或无权限
echo.
echo --- 私有目录 (%PRIVATE_DIR%) ---
adb shell ls -la "%PRIVATE_DIR%" 2>nul || echo [WARN] 私有目录不存在
echo.

echo [INFO] 检查浏览器数据持久化 (browser_data.json)...
set DATA=
for /f "delims=" %%a in ('adb shell cat "/data/data/%PACKAGE%/files/browser_data.json" 2^>nul') do set DATA=%%a
if defined DATA (
    echo [OK] browser_data.json 存在
    echo %DATA:~0,200%...
) else (
    echo [WARN] browser_data.json 不存在或为空
)
echo.

echo [INFO] 检查 SharedPreferences (设置持久化)...
adb shell ls "/data/data/%PACKAGE%/shared_prefs/" 2>nul
echo.

echo [INFO] 检查运行时权限...
adb shell dumpsys package %PACKAGE% | findstr /C:"runtime permissions:" -A 20
echo.

echo ========================================
echo [OK] 快速验证完成
echo ========================================
echo.

set /p CHOICE=是否启动应用进行嗅探/播放快速测试? (y/N):
if /i "%CHOICE%"=="y" (
    echo [INFO] 启动应用...
    adb shell am start -n %PACKAGE%/.MainActivity --es browser_url "https://www.w3schools.com/html/mov_bbb.mp4"
    timeout /t 3 >nul
    echo [OK] 应用已启动，请手动验证嗅探下载和边下边播
)

pause