FROM ghcr.io/graalvm/native-image:22.2.0 AS build-clj-kondo

ARG CLJ_KONDO_VERSION=2025.02.20
RUN microdnf install -y gzip tar && \
    curl -LO  https://github.com/clj-kondo/clj-kondo/archive/refs/tags/v${CLJ_KONDO_VERSION}.tar.gz && \
    gunzip v${CLJ_KONDO_VERSION}.tar.gz && \
    tar -xvf v${CLJ_KONDO_VERSION}.tar

WORKDIR /app/clj-kondo-${CLJ_KONDO_VERSION}/
ENV GRAALVM_HOME /usr

RUN curl -o /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    chmod +x /usr/local/bin/lein

#for ARM
ENV PATH /usr/bin:$PATH
RUN script/compile && \
    mv ./clj-kondo /

FROM clojure:temurin-20-tools-deps-jammy
COPY --from=build-clj-kondo /clj-kondo /usr/local/bin/clj-kondo
COPY --from=build-clj-kondo /usr/local/bin/lein /usr/local/bin/lein
RUN chmod +x /usr/local/bin/lein

COPY entrypoint.sh /entrypoint.sh
COPY lib /lint-action-clj

WORKDIR /lint-action-clj

#for ARM
ENV PATH /usr/bin:$PATH
RUN clojure -Stree -P && rm -r .cpcache
ENTRYPOINT ["/entrypoint.sh"]
