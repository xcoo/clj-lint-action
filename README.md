# Clj-lint-action

Run some linters such as clj-kondo , kibit , eastwood and show results as warning.

# Usage

```yaml
    steps:
    - uses: actions/checkout@v2
    - uses: xcoo/clj-lint-action@v1.1.1
      with:
        linters: "\"all\""
        github_token: ${{ secrets.GITHUB_TOKEN }}
        runner: ":leiningen"
        base_sha: ${{ github.event.before }}
        eastwood_linters: "[:all]"
```

## about 'linters'

if you want to select linters,set variable 'linters' like this.

```yaml
        linters: "[\"clj-kondo\" \"kibit\" \"eastwood\" \"cljfmt\"]"
```
