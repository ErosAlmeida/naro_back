# Official MinIO images and archived binaries were removed upstream in September 2026.
# Build the same release already used locally; verify its official source archive.
FROM golang:1.25.12-bookworm@sha256:6359592445455f2dbe2412bed411336035bc019a50017720d77454ffdd6d0f82 AS build
WORKDIR /src
RUN curl --fail --location --silent --show-error \
      https://codeload.github.com/minio/minio/tar.gz/refs/tags/RELEASE.2025-09-07T16-13-09Z \
      --output /tmp/minio.tar.gz \
    && echo 'c9598dcce3440977e79f787f2ba0e7e4d92c8d556bd51e7cef3785bafd6635f3  /tmp/minio.tar.gz' | sha256sum --check \
    && tar --extract --gzip --file /tmp/minio.tar.gz --strip-components=1 \
    && rm /tmp/minio.tar.gz
# Limit parallel compilation to fit the small CodeBuild environment.
RUN CGO_ENABLED=0 GOTOOLCHAIN=local go build -p 2 -trimpath \
    -ldflags '-s -w -X github.com/minio/minio/cmd.Version=2025-09-07T16:13:09Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-09-07T16-13-09Z -X github.com/minio/minio/cmd.CommitID=07c3a429bfed433e49018cb0f78a52145d4bedeb' \
    -o /minio .

FROM debian:bookworm-slim@sha256:3783cc01769c7b2b1b83a5c5ad96c815348e28ed7da68e2e3687004faa906251
COPY --from=build /minio /usr/local/bin/minio
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /src/LICENSE /usr/share/doc/minio/LICENSE
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="07c3a429bfed433e49018cb0f78a52145d4bedeb" \
      org.opencontainers.image.version="RELEASE.2025-09-07T16-13-09Z" \
      org.opencontainers.image.licenses="AGPL-3.0-only"
EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
