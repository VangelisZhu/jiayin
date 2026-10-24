FROM python:3.12-slim
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=2 update && apt-get install -y --no-install-recommends ffmpeg gosu \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --default-timeout=120 --retries=3 -r requirements.txt
COPY jiayin_manager ./jiayin_manager
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8780
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import os,urllib.request; urllib.request.urlopen('http://'+os.getenv('JIAYIN_HEALTH_HOST','127.0.0.1')+':8780/health')"
ENTRYPOINT ["/entrypoint.sh"]
CMD ["python", "-m", "jiayin_manager", "serve", "--host", "0.0.0.0"]
