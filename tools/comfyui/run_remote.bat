@echo off
chcp 65001 >nul
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
echo  本机 IPv4 地址（其它设备用它 + :8188 访问）：
for /f %%i in ('powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 ^| Where-Object {$_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*'}).IPAddress" 2^>nul') do echo    %%i:8188
echo.
echo ========================================
echo.

"%PY%" -s "%~dp0ComfyUI\main.py" --windows-standalone-build --listen --preview-method auto
pause