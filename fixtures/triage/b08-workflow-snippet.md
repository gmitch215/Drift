My workflow:

```yaml
jobs:
  build:
    runs-on: macos-14
    steps:
      - uses: actions/setup-node@v4
        with:
          node-version: 20.12.2
```

It fails with `npm ERR! code ELIFECYCLE`.
