# fio for disk latency measurements.
FROM alpine:3.20
RUN apk add --no-cache fio
ENTRYPOINT ["fio"]
