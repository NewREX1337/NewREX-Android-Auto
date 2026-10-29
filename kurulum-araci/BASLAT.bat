@echo off
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kurulum.ps1"
if errorlevel 1 (
  echo.
  echo   Arac beklenmedik sekilde durdu. sonuc.txt dosyasini gelistiriciye gonder.
  pause
)
