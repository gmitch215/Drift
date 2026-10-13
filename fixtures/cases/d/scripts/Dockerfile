ARG PHP
FROM composer:2 AS composer
FROM php:${PHP}
RUN if grep -qs bullseye /etc/apt/sources.list; then printf 'deb http://archive.debian.org/debian bullseye main\ndeb http://archive.debian.org/debian-security bullseye-security main\n' > /etc/apt/sources.list; fi \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && apt-get install -y --no-install-recommends git unzip ca-certificates procps libicu-dev libzip-dev libgmp-dev \
    && docker-php-ext-install -j4 intl zip bcmath gmp pcntl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=composer /usr/bin/composer /usr/local/bin/composer
