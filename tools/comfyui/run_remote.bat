@echo off
chcp 936 >nul
setlocal
cd /d "%~dp0"

rem 放在 ComfyUI 便携版根目录：与 ComfyUI\、python_embeded\ 同级。
set "PY=%~dp0python_embeded\python.exe"
if not exist "%PY%" set "PY=%~dp0python\python.exe"

if not exist "%PY%" (
    echo [错误] 找不到 Python。
    echo         请把本脚本放到 ComfyUI 便携版根目录（与 ComfyUI、python_embeded 同级）。
    pause
    exit /b 1
)
if not exist "%~dp0ComfyUI\main.py" (
    echo [错误] 找不到 ComfyUI\main.py。
    echo         本脚本必须和 ComfyUI 文件夹放在一起。
    pause
    exit /b 1
)

echo ========================================
echo  ComfyUI 远程模式启动中...
echo ========================================
echo.
echo  [!] --listen 会把 ComfyUI 暴露到局域网（以及任何能连到本机 8188 的网络），
echo      ComfyUI 默认没有登录认证。只建议在可信网络使用；
echo      需要公网访问时请用 VPN / 内网穿透，并在外层加访问控制。
echo.
echo  可用的本机 IPv4（手机填 http://IP:8188）：
set "FOUND_IP="
if exist "%~dp0list-ip.ps1" (
    for /f "tokens=1,2 delims=|" %%a in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0list-ip.ps1" 2^>nul') do (
        echo    %%b:8188    ^(%%a^)
        set "FOUND_IP=1"
    )
)
if not defined FOUND_IP (
    echo    PowerShell 没取到地址，改用 ipconfig：
    for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do echo    %%a:8188
)
echo.
echo  提示：同时列出多块网卡时，选和手机同一网络的那个；
echo        100.x 开头的是 Tailscale，手机也要装 Tailscale 才能用。
echo.
echo ========================================
echo.

"%PY%" -s "%~dp0ComfyUI\main.py" --windows-standalone-build --listen --preview-method auto
pause