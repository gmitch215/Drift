# Set Fixtures

Capsule sets for `drift diagnose --set`. A file name starting with `pass` or `fail` gives the
outcome of the run it describes.

- `toolchain` holds four hand-written capsules in which `tool.node.version` is the only value that
  separates the failing capsules from the passing ones.
- `proxy` holds the green and red capsules of the benchmark scenario `network-proxy-decoy`, repeats
  1 and 2, copied from `bench/data/capsules`. `env.HTTP_PROXY` separates; `env.NO_PROXY` is the
  decoy.
