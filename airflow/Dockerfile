FROM apache/airflow:2.9.2-python3.12

USER airflow

RUN pip install --no-cache-dir \
    opentelemetry-sdk==1.24.0 \
    opentelemetry-exporter-otlp-proto-http==1.24.0 \
    openinference-semantic-conventions==0.1.9 \
    requests==2.31.0
