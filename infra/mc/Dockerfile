FROM golang:1.25.4-alpine AS build
RUN apk add --no-cache git
RUN git clone --depth 1 --branch RELEASE.2025-08-13T08-35-41Z https://github.com/minio/mc.git /src
WORKDIR /src
RUN go build -o /mc .
FROM alpine:3.22
RUN apk add --no-cache ca-certificates
COPY --from=build /mc /usr/local/bin/mc
ENTRYPOINT ["mc"]
