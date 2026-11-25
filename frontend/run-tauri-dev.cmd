@echo off
setlocal
cd /d "%~dp0"

if not exist "node_modules\.bin\tauri.cmd" (
  echo Missing node_modules\.bin\tauri.cmd
  echo Run: npm.cmd install
  exit /b 1
)

call node_modules\.bin\tauri.cmd dev
