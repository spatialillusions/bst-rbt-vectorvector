import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import com.greensopinion.vectorvector.elevation.mapterhorn.ElevationGrid;
/** Isolated z13 resampling/materialization prototype; not wired into production. */
public class FusedResampleProbe {
 static volatile Object sink;
 static ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
 static double[] baseline(ElevationGrid grid,int ox,int oy){float[] f=grid.resample(ox,oy,256,512).getData();double[] d=new double[f.length];for(int i=0;i<f.length;i++)d[i]=f[i];return d;}
 static double[] fused(ElevationGrid grid,int ox,int oy){
  float[] input=grid.getData();int size=512;int[] left=new int[size],right=new int[size];float[] fraction=new float[size];
  for(int x=0;x<size;x++){double sx=ox+(x+.5)*.5-.5;int ix=(int)Math.floor(sx);fraction[x]=(float)(sx-ix);left[x]=Math.max(0,Math.min(511,ix));right[x]=Math.max(0,Math.min(511,ix+1));}
  double[] out=new double[size*size];for(int y=0;y<size;y++){double sy=oy+(y+.5)*.5-.5;int iy=(int)Math.floor(sy);float fy=(float)(sy-iy);int a=Math.max(0,Math.min(511,iy))*512,b=Math.max(0,Math.min(511,iy+1))*512;
   for(int x=0;x<size;x++){float fx=fraction[x];float top=input[a+left[x]]*(1-fx)+input[a+right[x]]*fx;float bottom=input[b+left[x]]*(1-fx)+input[b+right[x]]*fx;float value=top*(1-fy)+bottom*fy;out[y*size+x]=value;}
  }return out;
 }
 public static void main(String[] args){
  bean.setThreadAllocatedMemoryEnabled(true);float[] input=new float[512*512];for(int i=0;i<input.length;i++)input[i]=(float)(1000*Math.sin(i*.002));input[0]=Float.NaN;input[256*512+256]=Float.NaN;ElevationGrid grid=new ElevationGrid(512,input);
  for(int ox:new int[]{0,256})for(int oy:new int[]{0,256}){double[] a=baseline(grid,ox,oy),b=fused(grid,ox,oy);for(int i=0;i<a.length;i++)if(Double.doubleToLongBits(a[i])!=Double.doubleToLongBits(b[i]))throw new AssertionError("Rounding mismatch");}
  for(int i=0;i<50;i++){sink=baseline(grid,0,0);sink=fused(grid,0,0);}
  for(int round=0;round<4;round++)for(int v=0;v<2;v++){int mode=round%2==0?v:1-v,n=200;long tid=Thread.currentThread().threadId(),bytes=bean.getThreadAllocatedBytes(tid),cpu=bean.getCurrentThreadCpuTime();
   for(int i=0;i<n;i++)sink=mode==0?baseline(grid,0,0):fused(grid,0,0);
   long dt=bean.getCurrentThreadCpuTime()-cpu,b=bean.getThreadAllocatedBytes(tid)-bytes;
   System.out.printf(java.util.Locale.ROOT,"{\"mode\":\"%s\",\"round\":%d,\"cpu_us_per_grid\":%.3f,\"allocated_bytes_per_grid\":%d,\"bit_exact_samples_checked\":1048576}%n",mode==0?"production-materialize":"fused",round,dt/1000.0/n,b/n);
  }
 }
}
