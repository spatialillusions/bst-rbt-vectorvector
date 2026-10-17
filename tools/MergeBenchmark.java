// Run against either executable with: java -cp <jar> tools/MergeBenchmark.java <sources> <output>
// This invokes the actual Kotlin/JDBC merge. Verification is outside the timed section.
import com.greensopinion.vectorvector.ShardBuildKt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

class MergeBenchmark {
    public static void main(String[] args) throws Exception {
        var directory = new File(args[0]);
        var output = new File(args[1]);
        boolean verifyOnly = Arrays.asList(args).contains("--verify-existing");
        if (output.exists() && !verifyOnly) throw new IllegalArgumentException("Output already exists: " + output);
        if (verifyOnly && !output.isFile()) throw new IllegalArgumentException("Missing output to verify: " + output);
        var shards = new ArrayList<File>();
        for (int i = 0; ; i++) {
            var file = new File(directory, "source-" + i + ".mbtiles");
            if (!file.isFile()) break;
            shards.add(file);
        }
        if (shards.isEmpty()) throw new IllegalArgumentException("No sources");
        for (var shard : shards) {
            if (shard.getCanonicalFile().equals(output.getCanonicalFile())) throw new IllegalArgumentException("Output is an input shard");
        }
        boolean streaming = Arrays.asList(args).contains("--streaming-verify");
        int pageSize = Arrays.stream(args).filter(a -> a.startsWith("--page-size="))
            .mapToInt(a -> Integer.parseInt(a.substring("--page-size=".length()))).findFirst().orElse(0);
        int lookups = Arrays.stream(args).filter(a -> a.startsWith("--random-lookups="))
            .mapToInt(a -> Integer.parseInt(a.substring("--random-lookups=".length()))).findFirst().orElse(0);
        if (lookups > 0 && !streaming) throw new IllegalArgumentException("Random lookups require synthetic streaming verification");
        var seed = new ArrayList<byte[]>();
        int stride = 0;
        if (streaming) {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + shards.getFirst().getAbsolutePath()); var s = c.createStatement()) {
                try (var r = s.executeQuery("SELECT max(tile_row)+1 FROM tiles")) { r.next(); stride = r.getInt(1); }
                try (var r = s.executeQuery("SELECT tile_data FROM tiles ORDER BY tile_row,tile_column")) {
                    while (r.next()) seed.add(r.getBytes(1));
                }
            }
        }
        long start = System.nanoTime();
        long cpuStart = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
        if (verifyOnly) {
            System.out.println("VERIFY existing benchmark output");
        } else if (pageSize == 0) {
            ShardBuildKt.mergeShardMbtiles(output, shards);
        } else {
            // Optional overload is absent from historical executables; keep their
            // default comparison path source-compatible with this helper.
            ShardBuildKt.class.getDeclaredMethod("mergeShardMbtiles", File.class, java.util.List.class, int.class)
                .invoke(null, output, shards, pageSize);
        }
        // Include an explicit final fsync for the original implementation too.
        if (!verifyOnly) {
            try (var channel = FileChannel.open(output.toPath(), StandardOpenOption.WRITE)) { channel.force(true); }
        }
        double seconds = verifyOnly ? 0 : (System.nanoTime() - start) / 1e9;
        double cpuSeconds = verifyOnly ? 0 : (ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos() - cpuStart) / 1e9;
        if (!verifyOnly) System.out.printf(java.util.Locale.ROOT, "MERGE_TIMED_COMPLETE seconds=%.6f bytes=%d%n", seconds, output.length());
        System.out.println("VERIFY full SQLite integrity");
        var hash = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + output.getAbsolutePath()); var s = c.createStatement()) {
            // Keep a bounded working set large enough for interleaved index checks.
            s.execute("PRAGMA cache_size=-524288");
            try (var r = s.executeQuery("PRAGMA integrity_check")) {
                if (!r.next() || !r.getString(1).equals("ok")) throw new IllegalStateException("Integrity check failed");
            }
            s.execute("PRAGMA cache_size=-16384");
            System.out.println("VERIFY tile keys and payloads");
            try (var r = s.executeQuery("SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles" +
                    (streaming ? "" : " ORDER BY zoom_level,tile_column,tile_row"))) {
                while (r.next()) {
                    var payload = r.getBytes(4);
                    if (streaming) {
                        int shard = r.getInt(3) / stride;
                        int item = (r.getInt(3) % stride) * 8192 + r.getInt(2);
                        if (r.getInt(1) != 13 || shard < 0 || shard >= shards.size() || item < 0 || item >= seed.size()
                                || !Arrays.equals(payload, seed.get(item))) throw new IllegalStateException("Synthetic payload/key mismatch");
                    }
                    hash.update(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(r.getInt(1)).putInt(r.getInt(2)).putInt(r.getInt(3)).putInt(payload.length).array());
                    hash.update(payload);
                    count++;
                    if (count % 1_000_000 == 0) System.out.println("VERIFY tiles=" + count);
                }
            }
        }
        if (streaming && count != (long)shards.size() * seed.size()) throw new IllegalStateException("Tile count mismatch");
        if (lookups > 0) {
            var random = new java.util.Random(20261017);
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + output.getAbsolutePath()); var s = c.createStatement()) {
                s.execute("PRAGMA cache_size=-2048");
                int actualPageSize;
                try (var r = s.executeQuery("PRAGMA page_size")) { r.next(); actualPageSize = r.getInt(1); }
                try (var query = c.prepareStatement("SELECT tile_data FROM tiles WHERE zoom_level=13 AND tile_column=? AND tile_row=?")) {
                    long lookupStart = System.nanoTime();
                    for (int i = 0; i < lookups; i++) {
                        int shard = random.nextInt(shards.size()), item = random.nextInt(seed.size());
                        query.setInt(1, item % 8192);
                        query.setInt(2, shard * stride + item / 8192);
                        try (var r = query.executeQuery()) {
                            if (!r.next() || !Arrays.equals(r.getBytes(1), seed.get(item))) throw new IllegalStateException("Lookup payload mismatch");
                        }
                    }
                    System.out.printf(java.util.Locale.ROOT, "LOOKUP_RESULT {\"queries\":%d,\"seconds\":%.6f,\"page_size\":%d,\"cache_kib\":2048}%n",
                        lookups, (System.nanoTime() - lookupStart) / 1e9, actualPageSize);
                }
            }
        }
        var digest = HexFormat.of().formatHex(hash.digest());
        System.out.printf(java.util.Locale.ROOT,
            "%s {\"seconds\":%.6f,\"cpu_seconds\":%.6f,\"bytes\":%d,\"tiles\":%d,\"sha256\":\"%s\",\"verification\":\"%s\"}%n",
            verifyOnly ? "VERIFICATION_RESULT" : "MERGE_RESULT", seconds, cpuSeconds, output.length(), count, digest, streaming ? "streaming_synthetic" : "ordered_digest");
        // Only remove this benchmark's verified output, never input shards.
        Files.delete(output.toPath());
    }
}
