The `package.json` files under `node_modules` keep the `name`, `version`, `os`, `cpu` and `libc`
fields of the registry document `https://registry.npmjs.org/<name>/<version>`, read on 2026-10-15.
They are the variants npm installs on an Apple Silicon Mac. `node_modules/esbuild` is the root
package, whose `optionalDependencies` lists every platform package and which has no `os` or `cpu`.
