FROM alpine:3.10

FROM borkdude/clj-kondo AS clj-kondo

FROM clojure AS lein
FROM clojure:tools-deps-alpine

COPY --from=clj-kondo /usr/local/bin/clj-kondo /usr/local/bin/clj-kondo

RUN apk add git


ADD https://raw.github.com/technomancy/leiningen/stable/bin/lein /usr/local/bin/lein
RUN chmod 744 /usr/local/bin/lein


COPY LICENSE README.md /

COPY entrypoint.sh /entrypoint.sh
COPY lib /lint-action-clj

ENTRYPOINT ["/entrypoint.sh"]
