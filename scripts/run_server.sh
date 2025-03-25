#!/bin/bash
# scripts/run_server.sh

# Проверка наличия аргумента для файла конфигурации
if [ -z "$1" ]; then
    echo "Используется файл конфигурации по умолчанию."
    java -jar ../target/truck-loading-server.jar
else
    echo "Используется файл конфигурации: $1"
    java -jar ../target/truck-loading-server.jar --config "$1"
fi