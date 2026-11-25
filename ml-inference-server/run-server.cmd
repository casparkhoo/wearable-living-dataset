@echo off
setlocal
cd /d "%~dp0"

set "VENV_DIR=venv-py311"
set "VENV_PY=%VENV_DIR%\Scripts\python.exe"
set "DEPS_MARKER=%VENV_DIR%\.deps-installed"
set "WEIGHTS_FILE=weights\papagei_s.pt"

if not exist "%VENV_PY%" (
  echo Creating Python virtual environment...
  py -3.11 -m venv "%VENV_DIR%"
  if errorlevel 1 (
    echo Failed to create a Python 3.11 virtual environment. Install Python 3.11 and try again.
    exit /b 1
  )
)

if not exist "%DEPS_MARKER%" (
  echo Installing Python dependencies...
  "%VENV_PY%" -m pip install --upgrade pip
  if errorlevel 1 exit /b 1
  "%VENV_PY%" -m pip install -r requirements.txt
  if errorlevel 1 exit /b 1
  type nul > "%DEPS_MARKER%"
)

if not exist "%WEIGHTS_FILE%" (
  echo Model weights not found. Downloading PaPaGei weights...
  "%VENV_PY%" download_weights.py
  if errorlevel 1 exit /b 1
)

if not exist "papagei" (
  echo Missing papagei directory.
  echo Clone or copy the PaPaGei source into ml-inference-server\papagei before starting.
  exit /b 1
)

echo Starting PaPaGei inference server at http://127.0.0.1:5000
"%VENV_PY%" app.py
