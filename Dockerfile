FROM ghcr.io/graalvm/native-image:22.2.0 AS build-clj-kondo

RUN microdnf install -y gzip tar && \
    curl -LO  https://github.com/clj-kondo/clj-kondo/archive/refs/tags/v2023.04.14.tar.gz && \
    gunzip v2023.04.14.tar.gz && \
    tar -xvf v2023.04.14.tar

WORKDIR /app/clj-kondo-2023.04.14/
ENV GRAALVM_HOME /usr

RUN curl -o /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    chmod +x /usr/local/bin/lein

#for ARM
ENV PATH /usr/bin:$PATH
RUN script/compile && \
    mv ./clj-kondo /

FROM clojure:tools-deps-jammy
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
