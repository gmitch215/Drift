FROM ubuntu:24.04

ARG TARGETARCH
COPY bin/${TARGETARCH}/drift /usr/local/bin/drift
COPY studio /usr/local/share/drift/studio
COPY doc /usr/local/share/doc/drift

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["drift"]
CMD ["serve", "--unsafe-bind-all"]
