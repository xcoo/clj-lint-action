FROM cljkondo/clj-kondo:2022.05.31-alpine AS clj-kondo
FROM clojure:tools-deps-alpine

COPY --from=clj-kondo /bin/clj-kondo /usr/local/bin/clj-kondo

RUN apk add git

ADD https://raw.github.com/technomancy/leiningen/stable/bin/lein /usr/local/bin/lein
RUN chmod 744 /usr/local/bin/lein

COPY entrypoint.sh /entrypoint.sh
COPY lib /lint-action-clj

WORKDIR /lint-action-clj
RUN clojure -Stree -P && rm -r .cpcache

ENTRYPOINT ["/entrypoint.sh"]
