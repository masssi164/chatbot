FROM alpine:3.20

RUN apk add --no-cache bash curl jq

COPY seed-models.sh /usr/local/bin/seed-models.sh

ENTRYPOINT ["/usr/local/bin/seed-models.sh"]
