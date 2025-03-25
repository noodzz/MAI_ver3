@echo off
setlocal

rem Проверка наличия аргумента для файла конфигурации
if "%1"=="" (
    echo Используется файл конфигурации по умолчанию.
    java -jar "..\target\truck-loading-server.jar"
) else (
    echo Используется файл конфигурации: %1
    java -jar "..\target\truck-loading-server.jar" --config "%1"
)

pause
endlocal