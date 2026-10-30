FROM ubuntu:26.04

ARG TARGETARCH
COPY bin/${TARGETARCH}/drift /usr/local/bin/drift
COPY studio /usr/local/share/drift/studio
COPY doc /usr/local/share/doc/drift

RUN mkdir /work && chown 10001:10001 /work
WORKDIR /work

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["drift"]
CMD ["serve", "--unsafe-bind-all"]
