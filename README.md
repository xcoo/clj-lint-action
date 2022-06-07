# Clj-lint-action

Run some linters such as clj-kondo , kibit , eastwood and show results as warning.

# Usage

```yaml
    steps:
    - uses: actions/checkout@v2
    - uses: xcoo/clj-lint-action@v1.1.10
      with:
        linters: "\"all\""
        github_token: ${{ secrets.GITHUB_TOKEN }}
        runner: ":leiningen"
        base_sha: ${{ github.event.pull_request.base.sha||github.event.before }}
        eastwood_linters: "[:all]"
        linter_options: "{}"
```

## about 'linters'

if you want to select linters,set variable 'linters' like this.

```yaml
        linters: "[\"clj-kondo\" \"kibit\" \"eastwood\" \"cljfmt\"]"
```

## about `linter_options`
`linter_options` is the options for clj-kondo and eastwood.
```
linter_options: "{:clj-kondo {:linters {:redundant-let {:level :info}}}\
                  :eastwood {:exclude-namespaces [tmp-clj.core2]}}"
```
