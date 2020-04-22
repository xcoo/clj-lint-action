# Clj-lint-action

Run some linters such as clj-kondo , kibit , eastwood and show results as warning.

# Usage

```yaml
    steps:
    - uses: actions/checkout@v2
    - uses: technote-space/get-diff-action@v1
      id: git_diff
      with:
        SUFFIX_FILTER: .clj
        SEPARATOR: ' '
    - uses: xcoo/clj-lint-action@v1.0.0
      with:
        linters: "\"all\""
        github_token: ${{ secrets.GITHUB_TOKEN }}
        runner: ":leiningen"
        usefiles: "true"
        files:  ${{ steps.git_diff.outputs.diff }}
```

## about 'linters'

if you want to select linters,set variable 'linters' like this.

```yaml
        linters: "[\"clj-kondo\" \"kibit\" \"eastwood\" \"cljfmt\"]"
```
