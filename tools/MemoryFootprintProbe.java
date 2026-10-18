import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import org.locationtech.jts.io.*;
import java.security.MessageDigest;
import java.util.*;
import com.sun.management.ThreadMXBean;
import com.greensopinion.vectorvector.*;
import com.greensopinion.vectorvector.osm.*;
import com.greensopinion.vectorvector.pmtiles.*;
import com.greensopinion.vectorvector.metrics.*;
import com.greensopinion.vectorvector.elevation.mapterhorn.*;
import com.greensopinion.vectorvector.sink.contour.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/** Read-only research probe against the producer JAR; not a production CLI. */
public class MemoryFootprintProbe {
 static volatile Object retained;
 static ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
 static long allocated(){return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());}
 static long cpu(){return bean.getCurrentThreadCpuTime();}
 static long used(){System.gc();System.gc();return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
 static void record(String name,Object...pairs){StringBuilder s=new StringBuilder("{\"stage\":\"").append(name).append("\"");for(int i=0;i<pairs.length;i+=2)s.append(",\"").append(pairs[i]).append("\":").append(pairs[i+1]);System.out.println(s.append('}'));}
 static class Features {List<Glacier> glaciers;List<Peak> peaks;GlacierIndex ice;PeakIndex peakIndex;}
 static Features load(String pbf,String wkb,boolean packed){
   GeometryFactory f=packed?new GeometryFactory(new PrecisionModel(),0,PackedCoordinateSequenceFactory.DOUBLE_FACTORY):new GeometryFactory();
   Features h=new Features();h.peaks=new PeakLoader(new File(pbf)).load();h.glaciers=new ArrayList<>(new GlacierLoader(new File(pbf),f).load());
   List<Glacier> extra=new GlacierWkbLoader(new File(wkb)).load();
   for(Glacier g:extra)h.glaciers.add(packed?new Glacier(g.getOsmId(),f.createGeometry(g.getGeometry())):g);
   index(h);return h;
 }
 static void index(Features h){h.ice=new GlacierIndex(h.glaciers,4096,256,6,1024,new GeometryFactory());h.peakIndex=new PeakIndex(h.peaks,9,13,(lon,lat)->null,4096,80,50,12);}
 static Envelope envelope(int x,int y){double h=.0625;return new Envelope((x-h)/64*360-180,(x+1+h)/64*360-180,lat(y+1+h),lat(y-h));}
 static double lat(double y){return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*y/64))));}
 static long points(List<Glacier> g){long n=0;for(Glacier a:g)n+=a.getGeometry().getNumPoints();return n;}
 static Features select(Features h,Envelope e){Features a=new Features();a.glaciers=new ArrayList<>();a.peaks=new ArrayList<>();for(Glacier g:h.glaciers)if(e.intersects(g.getGeometry().getEnvelopeInternal()))a.glaciers.add(g);for(Peak p:h.peaks)if(e.contains(p.getLon(),p.getLat()))a.peaks.add(p);index(a);return a;}
 static void regional(int x,int y){Features h=(Features)retained;Features a=select(h,envelope(x,y));record("region-counts","x",x,"y",y,"glaciers",a.glaciers.size(),"glacier_points",points(a.glaciers),"peaks",a.peaks.size());retained=a;}
 static void nullable(DataOutputStream d,String x)throws IOException{d.writeBoolean(x!=null);if(x!=null)d.writeUTF(x);}
 static String nullable(DataInputStream d)throws IOException{return d.readBoolean()?d.readUTF():null;}
 static void saveRegion(File file,Features h)throws Exception{
  try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){
   d.writeInt(1);d.writeInt(h.glaciers.size());WKBWriter w=new WKBWriter(2);
   for(Glacier g:h.glaciers){d.writeUTF(g.getOsmId());byte[] b=w.write(g.getGeometry());d.writeInt(b.length);d.write(b);}
   d.writeInt(h.peaks.size());for(Peak p:h.peaks){d.writeLong(p.getOsmId());d.writeDouble(p.getLon());d.writeDouble(p.getLat());nullable(d,p.getName());nullable(d,p.getNameEn());d.writeBoolean(p.getEle()!=null);if(p.getEle()!=null)d.writeInt(p.getEle());nullable(d,p.getEleSource());d.writeUTF(p.getKind());}
  }
 }
 static Features readRegion(File file)throws Exception{
  Features h=new Features();h.glaciers=new ArrayList<>();h.peaks=new ArrayList<>();
  try(DataInputStream d=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
   if(d.readInt()!=1)throw new IOException();int n=d.readInt();WKBReader w=new WKBReader();
   for(int i=0;i<n;i++){String id=d.readUTF();int len=d.readInt();h.glaciers.add(new Glacier(id,w.read(d.readNBytes(len))));}
   n=d.readInt();for(int i=0;i<n;i++){long id=d.readLong();double x=d.readDouble(),y=d.readDouble();String name=nullable(d),en=nullable(d);Integer e=d.readBoolean()?d.readInt():null;String src=nullable(d),kind=d.readUTF();h.peaks.add(new Peak(id,x,y,name,en,e,src,kind));}
  }index(h);return h;
 }
 static void saveAndCheck(Features global,int x,int y,String directory)throws Exception{
  Features h=select(global,envelope(x,y));File file=new File(directory,"regional-"+x+"-"+y+".bin");saveRegion(file,h);Features loaded=readRegion(file);
  long coordinatePoints=points(h.glaciers);int checks=0;
  for(int z:new int[]{9,12,13}){
   int f=1<<(z-6);for(int dx:new int[]{0,f/2,f-1})for(int dy:new int[]{0,f/2,f-1}){
    TileId id=new TileId(z,x*f+dx,y*f+dy);Geometry aa=global.ice.forTile(id),bb=loaded.ice.forTile(id);
    if((aa==null)!=(bb==null) || aa!=null&&!aa.equalsTopo(bb))throw new AssertionError("Ice mismatch "+id);
    byte[] pa=global.peakIndex.layer(id)==null?null:global.peakIndex.layer(id).toByteArray(),pb=loaded.peakIndex.layer(id)==null?null:loaded.peakIndex.layer(id).toByteArray();
    if(!Arrays.equals(pa,pb))throw new AssertionError("Peak mismatch "+id);checks++;
   }
  }
  record("prepared-region","x",x,"y",y,"glaciers",h.glaciers.size(),"glacier_points",coordinatePoints,"peaks",h.peaks.size(),"bytes",file.length(),"matching_mask_and_peak_tiles",checks);
 }
 static void reload(String[] a)throws Exception{long base=used(),t=System.nanoTime();retained=readRegion(new File(a[1]));long dt=System.nanoTime()-t;Features h=(Features)retained;record("reload-region","retained_heap_bytes",used()-base,"load_wall_seconds",dt/1e9,"glaciers",h.glaciers.size(),"peaks",h.peaks.size());}
 static void osm(String[] a)throws Exception{long base=used(),t=System.nanoTime();retained=load(a[1],a[2],Boolean.parseBoolean(a[3]));long global=used();Features h=(Features)retained;record("global","retained_heap_bytes",global-base,"load_wall_seconds",(System.nanoTime()-t)/1e9,"glaciers",h.glaciers.size(),"glacier_points",points(h.glaciers),"peaks",h.peaks.size());if(a.length>6){saveAndCheck(h,29,16,a[6]);saveAndCheck(h,47,26,a[6]);}h=null;
   regional(Integer.parseInt(a[4]),Integer.parseInt(a[5]));record("regional","retained_heap_bytes",used()-base);
 }
 static int tileX(double lon,int z){return (int)((lon+180)/360*(1<<z));}
 static int tileY(double lat,int z){double r=Math.toRadians(lat);return (int)((1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*(1<<z));}
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static long cacheSize(Object o,String name)throws Exception{return ((com.google.common.cache.Cache<?,?>)field(o,name)).size();}
 static void grids(String[] a)throws Exception{
  int cap=Integer.parseInt(a[2]),width=Integer.parseInt(a[3]);long base=used();
  FileRangeSource file=new FileRangeSource(new File(a[1]));PmtilesReader reader=new PmtilesReader(file,file);MapterhornSourceSet sources=new MapterhornSourceSet(List.of(reader),cap);MapterhornElevationDataStore store=new MapterhornElevationDataStore(sources,512,new SingletonMetricsProvider(),4,cap);
  Method sample=Arrays.stream(ElevationTile.class.getMethods()).filter(m->m.getName().startsWith("get-") && m.getParameterCount()==2).findFirst().orElseThrow();
  int cx=tileX(7.7,13),cy=tileY(46,13);long start=cpu(),alloc=allocated();long checksum=0;int requests=0;
  for(int x=cx;x<cx+width;x++)for(int y=cy;y<cy+width;y++)for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
    ElevationTile tile=store.get(new TileId(13,x+dx,y+dy)); // Native get is inline-mangled; hash the backing float arrays below.
    checksum=31*checksum+Double.doubleToLongBits((Double)sample.invoke(tile,256,256));requests++;
  }
  long cpuNs=cpu()-start,bytes=allocated()-alloc;
  Set<Object> arrays=Collections.newSetFromMap(new IdentityHashMap<>());
  for(String name:List.of("tileCache","nativeCache"))for(Object v:((com.google.common.cache.Cache<?,?>)field(store,name)).asMap().values()){
    Object g=v instanceof Optional?((Optional<?>)v).orElse(null):field(v,"grid");if(g instanceof ElevationGrid)arrays.add(((ElevationGrid)g).getData());
  }
  for(Object v:((com.google.common.cache.Cache<?,?>)field(sources,"decoded")).asMap().values())arrays.add(((ElevationGrid)v).getData());
  long gridBytes=0;for(Object v:arrays){float[] f=(float[])v;gridBytes+=(long)f.length*4;}
  retained=store;record("grids","sample_checksum",checksum,"capacity",cap,"requests",requests,"cpu_seconds",cpuNs/1e9,"allocated_bytes",bytes,"retained_heap_bytes",used()-base,"distinct_grid_array_bytes",gridBytes,"tile_entries",cacheSize(store,"tileCache"),"native_entries",cacheSize(store,"nativeCache"),"decoded_entries",cacheSize(sources,"decoded"));store.close();
 }
 static void isolines(){int size=512;double[] data=new double[size*size];for(int y=0;y<size;y++)for(int x=0;x<size;x++)data[y*size+x]=1000+500*Math.sin(x*.04)*Math.cos(y*.03);int[] levels=new int[50];for(int i=0;i<50;i++)levels[i]=510+i*20;FastIsolines f=new FastIsolines(data,size,0,8);for(int i=0;i<4;i++)retained=f.generate(levels,0,size-1);long bytes=allocated(),t=cpu();int n=8;for(int i=0;i<n;i++)retained=f.generate(levels,0,size-1);long dt=cpu()-t,b=allocated()-bytes;long points=0;for(Object lines:((Map<?,?>)retained).values())for(Object line:(List<?>)lines)points+=((List<?>)line).size();record("isolines","points",points,"cpu_ms_per_tile",dt/1e6/n,"allocated_bytes_per_tile",b/n);}
 public static void main(String[] a)throws Exception{bean.setThreadAllocatedMemoryEnabled(true);switch(a[0]){case "osm":osm(a);break;case "reload":reload(a);break;case "grids":grids(a);break;case "isolines":isolines();break;default:throw new IllegalArgumentException();}}
}
