FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends build-essential ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://github.com/esnet/iperf/archive/refs/tags/3.22.tar.gz | tar xz \
    && cd iperf-3.22 \
    && ./configure --prefix=/usr \
    && make -j"$(nproc)" \
    && make install

FROM debian:bookworm-slim
COPY --from=build /usr/bin/iperf3 /usr/bin/iperf3
COPY --from=build /usr/lib/libiperf.so.0* /usr/lib/
RUN ldconfig
EXPOSE 5201
CMD ["iperf3", "-s"]
