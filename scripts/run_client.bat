@echo off
if "%1"=="" (
    echo Необходимо указать IP-адрес сервера.
    echo Пример: run_client.bat 192.168.1.10
    pause
    exit /b
)
java -jar "..\target\truck-loading-client.jar" %1
pause