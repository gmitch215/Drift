FROM eclipse-temurin:11-jdk-focal
RUN apt-get update && apt-get install -y --no-install-recommends libtinfo5 && rm -rf /var/lib/apt/lists/*
