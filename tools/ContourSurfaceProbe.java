// Sample the raw and production-smoothed terrain at reference contour vertices.
// Args: planet.pmtiles input.csv output.csv; no input archive or profile is modified.
import com.greensopinion.vectorvector.*;
import com.greensopinion.vectorvector.cli.CliOptions;
import com.greensopinion.vectorvector.elevation.ElevationDataStore;
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider;
import com.greensopinion.vectorvector.profile.RbtProfile;
import com.greensopinion.vectorvector.sink.contour.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import picocli.CommandLine;

class ContourSurfaceProbe {
    static double sample(BufferedGridTile grid, double x, double y) {
        int ix = (int)Math.floor(x), iy = (int)Math.floor(y), b = grid.getBuffer(), n = grid.getSize();
        double fx = x - ix, fy = y - iy;
        double[] d = grid.getData();
        int k = (iy + b) * n + ix + b;
        return (d[k] * (1-fx) + d[k+1]*fx)*(1-fy) + (d[k+n]*(1-fx)+d[k+n+1]*fx)*fy;
    }
    public static void main(String[] args) throws Exception {
        var options = new CliOptions();
        new CommandLine(options).parseArgs("--profile","rbt","--dataFormat","mapterhorn","--pmtiles",args[0],
            "--elevationCacheTiles","128","-f","mbtiles","-o","out");
        var factory = MainKt.class.getDeclaredMethod("cachingElevationDataStore",CliOptions.class,SingletonMetricsProvider.class);
        factory.setAccessible(true);
        var data = (ElevationDataStore)factory.invoke(null,options,new SingletonMetricsProvider());
        var profile = new RbtProfile(RbtProfile.Companion.getDEFAULT_SETTINGS(),9,13);
        var grouped = new LinkedHashMap<TileId,List<String[]>>();
        var inputLines = Files.readAllLines(Path.of(args[1]));
        for (String line : inputLines.subList(1,inputLines.size())) {
            var v=line.split(",");
            grouped.computeIfAbsent(new TileId(Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2])), k->new ArrayList<>()).add(v);
        }
        try (var writer = new PrintWriter(Files.newBufferedWriter(Path.of(args[2])))) {
            writer.println("z,x,y,px,py,ele,region,dataset,raw,smoothed,raw_max,smoothed_max,slope_degrees,normal_offset_m");
            for (var e : grouped.entrySet()) {
                var t=e.getKey();
                var area=new ElevationTileArea(t,data.get(t),data);
                var raw=GaussianBlur.INSTANCE.materialize(area,11);
                var smooth=GaussianBlur.INSTANCE.blur(area,11,profile.contourOptions(t).getSmoothing().getSigmaPx(),null);
                double rm=-Double.MAX_VALUE, sm=-Double.MAX_VALUE;
                int n=raw.getSize(), b=raw.getBuffer();
                for(int y=0;y<512;y++) for(int x=0;x<512;x++) {
                    rm=Math.max(rm,raw.getData()[(y+b)*n+x+b]);
                    sm=Math.max(sm,smooth.getData()[(y+b)*n+x+b]);
                }
                for(var v:e.getValue()) {
                    double x=Double.parseDouble(v[3])/8., y=Double.parseDouble(v[4])/8.;
                    double gradient=Math.hypot((sample(smooth,x+1,y)-sample(smooth,x-1,y))/2,
                        (sample(smooth,x,y+1)-sample(smooth,x,y-1))/2);
                    double latitude=Math.atan(Math.sinh(Math.PI*(1-2*(t.getY()+y/512.)/Math.scalb(1.,t.getZ()))));
                    double groundPixel=40075016.68557849*Math.cos(latitude)/(512*Math.scalb(1.,t.getZ()));
                    writer.printf(Locale.ROOT,"%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f%n",String.join(",",v),sample(raw,x,y),sample(smooth,x,y),rm,sm,
                        Math.toDegrees(Math.atan(gradient/groundPixel)),Math.abs(sample(smooth,x,y)-Double.parseDouble(v[5]))*groundPixel/gradient);
                }
            }
        } finally { ((Closeable)data).close(); }
    }
}
