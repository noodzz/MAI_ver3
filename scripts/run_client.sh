# scripts/run_client.sh
#!/bin/bash
if [ -z "$1" ]; then
    echo "Необходимо указать IP-адрес сервера."
    echo "Пример: ./run_client.sh 192.168.1.10"
    exit 1
fi
java -jar ../target/truck-loading-client.jar $1