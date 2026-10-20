// Cold glacier-root clipping against the actual global feature set. Read-only input.
// java -Xmx8g -cp <frozen.jar> tools/GlacierClipProbe.java <features.pbf> <new-output-dir>
import com.greensopinion.vectorvector.TileId;
import com.greensopinion.vectorvector.osm.GlacierIndex;
import com.greensopinion.vectorvector.osm.GlacierLoader;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

class GlacierClipProbe {
    public static void main(String[] args) throws Exception {
        var out = Path.of(args[1]);
        Files.createDirectory(out);
        var factory = new GeometryFactory();
        var glaciers = new GlacierLoader(new File(args[0]), factory).load();
        Files.writeString(out.resolve("loaded-glacier-ids.txt"), glaciers.stream()
            .map(g -> g.getOsmId()).collect(Collectors.joining("\n")));
        for (int repeat = 0; repeat < 2; repeat++) {
            for (var tile : new TileId[] {new TileId(6,24,16), new TileId(6,23,15)}) {
                var index = new GlacierIndex(glaciers, 4096, 256, 6, 1024L, factory);
                long start = System.nanoTime();
                var geometry = index.lonLatForTile(tile);
                double seconds = (System.nanoTime() - start) / 1e9;
                if (geometry == null) throw new IllegalStateException("Empty glacier query");
                var name = tile.getX() + "-" + tile.getY() + "-" + repeat;
                Files.write(out.resolve(name + ".wkb"), new WKBWriter().write(geometry));
                System.out.printf(Locale.ROOT,
                    "CLIP_RESULT {\"query\":\"%s\",\"seconds\":%.6f,\"points\":%d,\"area\":%.12f}%n",
                    name, seconds, geometry.getNumPoints(), geometry.getArea());
            }
        }
    }
}
