@echo off
setlocal

echo ============================================
echo   Midjay MP3 collector - Build EXE
echo ============================================
echo.

REM Percorso della JDK BellSoft Full
set JDK="C:\jdk\bellsoft-jdk-25.0.2-full"

REM Cartella del progetto
set PROJECT_DIR=%~dp0

REM Cartella di output
set DIST=%PROJECT_DIR%dist

echo Cartella progetto: %PROJECT_DIR%
echo Cartella output: %DIST%
echo.

REM Elimina la cartella dist se esiste
if exist "%DIST%" (
    echo Rimozione cartella dist esistente...
    rmdir /s /q "%DIST%"
)

REM Crea la cartella dist
echo Creazione nuova cartella dist...
mkdir "%DIST%"

echo.
echo Avvio jpackage...
%JDK%\bin\jpackage.exe ^
  --type app-image ^
  --name MidjayMp3Collector ^
  --input "%PROJECT_DIR%target" ^
  --main-jar midjaymp3collector-1.0.0-SNAPSHOT.jar ^
  --main-class it.andrea.midjaymp3collector.MidjayMp3Collector ^
  --dest "%DIST%" ^
  --app-version 1.0.0 ^
  --vendor "Andrea Bevilacqua" ^
  --description "Midjay MP3 collector" ^
  --copyright "� 2026 Andrea"

echo.
echo ============================================
echo   Build completato!
echo   EXE generato in: dist\MidjayMp3Collector
echo ============================================
echo.

pause
endlocal