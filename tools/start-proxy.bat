@echo off
rem Keep this file ASCII-only: cmd.exe reads BOM-less text as ANSI.
rem
rem Starts the LAN proxy so a phone on the same Wi-Fi can reach ComfyUI
rem (the ComfyUI host itself is only reachable over Tailscale).
rem
rem Double-click to start, keep the window open while testing, Ctrl+C to stop.
rem Edit TARGET / TARGET_PORT if the ComfyUI host changes.

setlocal
set TARGET=100.84.55.22
set TARGET_PORT=8188
set LISTEN_PORT=18188

rem Prefer node on PATH; fall back to the bundled runtime used by this machine.
set NODE=node
where node >nul 2>nul || set NODE=C:\Users\ASUS\.workbuddy\binaries\node\versions\22.22.2-6\node.exe

cd /d "%~dp0.."
echo Proxy listening on port %LISTEN_PORT% -^> %TARGET%:%TARGET_PORT%
echo Point the phone at http://^<this-pc-lan-ip^>:%LISTEN_PORT%
echo.
"%NODE%" tools\comfy-proxy.mjs %LISTEN_PORT% %TARGET% %TARGET_PORT% 0.0.0.0
