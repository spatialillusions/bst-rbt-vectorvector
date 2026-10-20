// Investigation-only harness: production terrain, contour, worker and MBTiles classes;
// no change to the shipping profile or CLI. Run with the executable on the classpath.
// Arguments: tile-list output.mbtiles sigma rbt|10m pmtiles [additional-pmtiles ...]
import com.greensopinion.vectorvector.*;
import com.greensopinion.vectorvector.cli.CliOptions;
import com.greensopinion.vectorvector.elevation.ElevationDataStore;
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider;
import com.greensopinion.vectorvector.sink.*;
import com.greensopinion.vectorvector.sink.contour.*;
import io.github.oshai.kotlinlogging.KotlinLogging;
import java.io.Closeable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import kotlin.jvm.functions.Function1;
import picocli.CommandLine;

class ContourDetailProbe {
    public static void main(String[] args) throws Exception {
        var keys = new HashSet<TileId>();
        int maxZoom = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank()) continue;
            var p = Arrays.stream(line.strip().split("/")).mapToInt(Integer::parseInt).toArray();
            keys.add(new TileId(p[0], p[1], p[2]));
            maxZoom = Math.max(maxZoom, p[0]);
        }
        var output = new File(args[1]);
        if (output.exists()) throw new IllegalArgumentException("Output exists: " + output);
        var cli = new ArrayList<String>(List.of("--profile", "rbt", "--dataFormat", "mapterhorn", "--elevationCacheTiles", "512",
            "-f", "mbtiles", "-o", output.getAbsoluteFile().getParent()));
        for (int i = 4; i < args.length; i++) cli.addAll(List.of("--pmtiles", args[i]));
        var options = new CliOptions();
        new CommandLine(options).parseArgs(cli.toArray(String[]::new));
        var metrics = new SingletonMetricsProvider();
        var factory = MainKt.class.getDeclaredMethod("cachingElevationDataStore", CliOptions.class, SingletonMetricsProvider.class);
        factory.setAccessible(true);
        long wallStart = System.nanoTime();
        var data = (ElevationDataStore)factory.invoke(null, options, metrics);
        try {
            Function1<Integer, Boolean> filter = args[3].equals("rbt") ? e -> e % 20 == 0 || e % 50 == 0 : null;
            var contour = new ContourOptions(1., "ele", "level", "contours", 4096, 1, 10, 10,
                Integer.MIN_VALUE, null, AttributeStyle.RBT, filter,
                new SmoothingOptions(Double.parseDouble(args[2]), 2), 2.5, 80);
            var schema = new LayerSchema("contours", "Experimental detail investigation", maxZoom, maxZoom,
                Map.of("ele", "Number", "index", "Number", "is_ice", "Number"));
            var repository = MainKt.createMbTilesRepository(options, "pbf", output, metrics, List.of(schema));
            var logger = KotlinLogging.INSTANCE.logger("ContourDetailProbe");
            long start = System.nanoTime();
            long cpuStart = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
            try (repository) {
                var sink = new VectorTileSink(t -> contour, repository, data, metrics, t -> null, List.of(), true);
                new Processor(new TileRange(0, maxZoom, 0, 0, 0, 0, null, keys), sink, metrics, 8, logger).process();
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            double cpuSeconds = (ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos() - cpuStart) / 1e9;
            System.out.printf(Locale.ROOT, "DETAIL_RESULT {\"seconds\":%.6f,\"cpu_seconds\":%.6f,\"wall_seconds\":%.6f,\"requested_tiles\":%d,\"bytes\":%d}%n",
                seconds, cpuSeconds, (System.nanoTime() - wallStart) / 1e9, keys.size(), output.length());
        } finally {
            ((Closeable)data).close();
        }
    }
}
