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
echo  ComfyUI 本地模式启动中...
echo ========================================
echo.
echo  仅本机可访问：http://127.0.0.1:8188
echo  [!] 手机、平板、其它电脑都连不上这个地址；
echo      要远程连接请改用 run_remote.bat。
echo.

"%PY%" -s "%~dp0ComfyUI\main.py" --windows-standalone-build
pause