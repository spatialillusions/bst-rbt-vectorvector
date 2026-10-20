import java.lang.management.ManagementFactory;
import java.util.*;
import com.sun.management.ThreadMXBean;
import com.greensopinion.vectorvector.sink.contour.*;
/** Isolated primitive-buffer prototype; preserves production Chaikin arithmetic. */
public class PrimitiveSmoothingProbe {
 static volatile Object sink;
 static final ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
 static double[] smooth(double[] points,int iterations){
  for(int k=0;k<iterations;k++){
   int n=points.length/2;if(n<3)return points;
   boolean closed=points[0]==points[2*n-2] && points[1]==points[2*n-1];
   int ring=closed?n-1:n,segments=closed?ring:n-1;
   double[] out=new double[(closed?2*ring+1:2*n)*2];int j=0;
   if(!closed){out[j++]=points[0];out[j++]=points[1];}
   for(int i=0;i<segments;i++){
    int next=(i+1)%ring;
    double ax=points[2*i],ay=points[2*i+1],bx=points[2*next],by=points[2*next+1];
    out[j++]=ax*.75+bx*.25;out[j++]=ay*.75+by*.25;
    out[j++]=ax*.25+bx*.75;out[j++]=ay*.25+by*.75;
   }
   out[j++]=closed?out[0]:points[2*n-2];out[j++]=closed?out[1]:points[2*n-1];
   if(j!=out.length)throw new AssertionError();points=out;
  }return points;
 }
 static void run(boolean closed){
  List<DoublePoint> list=new ArrayList<>();int count=1000;
  for(int i=0;i<count;i++)list.add(new DoublePoint(i,Math.sin(i*.1)*100));
  if(closed)list.add(list.get(0));Line line=new Line(list);double[] input=new double[list.size()*2];for(int i=0;i<list.size();i++){input[2*i]=list.get(i).getX();input[2*i+1]=list.get(i).getY();}
  List<DoublePoint> expected=Chaikin.INSTANCE.smooth(line,2).getPoints();double[] actual=smooth(input,2);if(actual.length!=2*expected.size())throw new AssertionError();
  for(int i=0;i<expected.size();i++)if(Double.doubleToLongBits(expected.get(i).getX())!=Double.doubleToLongBits(actual[2*i]) || Double.doubleToLongBits(expected.get(i).getY())!=Double.doubleToLongBits(actual[2*i+1]))throw new AssertionError("Different coordinate");
  for(int i=0;i<1000;i++){sink=Chaikin.INSTANCE.smooth(line,2);sink=smooth(input,2);}
  for(int round=0;round<4;round++)for(int variant=0;variant<2;variant++){
   int mode=(round%2==0)?variant:1-variant,n=5000;long tid=Thread.currentThread().threadId(),bytes=bean.getThreadAllocatedBytes(tid),cpu=bean.getCurrentThreadCpuTime();
   for(int i=0;i<n;i++)sink=mode==0?Chaikin.INSTANCE.smooth(line,2):smooth(input,2);
   long dt=bean.getCurrentThreadCpuTime()-cpu,b=bean.getThreadAllocatedBytes(tid)-bytes;
   System.out.printf(java.util.Locale.ROOT,"{\"closed\":%s,\"mode\":\"%s\",\"round\":%d,\"cpu_us_per_line\":%.3f,\"allocated_bytes_per_line\":%d,\"bit_exact_points\":%d}%n",closed,mode==0?"production":"primitive",round,dt/1000.0/n,b/n,expected.size());
  }
 }
 public static void main(String[] args){bean.setThreadAllocatedMemoryEnabled(true);run(false);run(true);}
}
