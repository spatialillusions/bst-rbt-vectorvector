
## Profiling

### Install
Download a release of the async profiler: https://github.com/async-profiler/async-profiler/releases/tag/nightly

### Steps

1. Run vectorvector
2. Run the profiler, e.g.

```sh
$ ./bin/asprof -d 30 -f /tmp/flamegraph.html $(ps aux | grep '[v]ectorvector' | grep MainKt  | awk '{print $2}')
```

3. Open the flamegraph:

```sh
$ open /tmp/flamegraph.html`
```
