"""真实新旧纵断面求解器完整剖面对拍与内核测量，不把复杂度变化换算游戏帧率。"""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
JAVA=Path(r'C:\Program Files\Java\jdk-21\bin')
SOURCE=r'''
package net.shiroha233.roadweaver.pathlogic.profile;
import java.util.*;import java.lang.management.ManagementFactory;
public class PairProbe{
 static volatile long sink;
 static final com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
 static long[] sample(ProfileDpSolver.Problem p,FrozenProfileDpSolver.Problem old,boolean modern){
  long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime(),sum=0;
  for(int i=0;i<4;i++)sum+=Double.doubleToLongBits(modern?ProfileDpSolver.solve(p).orElseThrow().totalCost()
       :FrozenProfileDpSolver.solve(old).orElseThrow().totalCost());
  sink=sum;
  return new long[]{System.nanoTime()-start,bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes};
 }
 public static void main(String[] args){
  var out=new StringBuilder("[");int fixture=0;
  for(int limit:new int[]{0,1,2,4,8,16}){
   int n=384,width=96;int[]lo=new int[n],hi=new int[n];double[][]cost=new double[n][width];
   for(int i=0;i<n;i++){
    lo[i]=64+(int)Math.round(4*Math.sin(i/19.));hi[i]=lo[i]+width-1;
    for(int j=0;j<width;j++)cost[i][j]=i%7==0?0:Math.abs(j-40-Math.sin(i/11.)*20);
   }
   int step=Math.max(1,(limit+1)/2);
   var p=new ProfileDpSolver.Problem(lo,hi,cost,step,null,limit);
   var old=new FrozenProfileDpSolver.Problem(lo,hi,cost,step,null,limit);
   var a=ProfileDpSolver.solve(p).orElseThrow();var b=FrozenProfileDpSolver.solve(old).orElseThrow();
   if(!Arrays.equals(a.profile(),b.profile())||Double.doubleToLongBits(a.totalCost())!=Double.doubleToLongBits(b.totalCost()))
    throw new AssertionError("完整剖面或代价改变");
   for(int i=0;i<5;i++){sample(p,old,false);sample(p,old,true);}
   long[][]before=new long[7][],after=new long[7][];
   for(int i=0;i<7;i++)if(i%2==0){before[i]=sample(p,old,false);after[i]=sample(p,old,true);}
    else{after[i]=sample(p,old,true);before[i]=sample(p,old,false);}
   if(fixture++>0)out.append(',');
   out.append("{\"limit\":").append(limit).append(",\"max_step\":").append(step)
    .append(",\"stations\":384,\"window_width\":96,\"solves_per_sample\":4,\"old_ns_allocated_bytes\":")
    .append(Arrays.deepToString(before)).append(",\"new_ns_allocated_bytes\":").append(Arrays.deepToString(after)).append('}');
  }
  System.out.println(out.append(']'));
 }
}
'''
def main():
 work=ROOT/'.analysis_tmp/profile-pair-probe'
 work.mkdir(parents=True,exist_ok=True)
 source=work/'PairProbe.java';source.write_text(SOURCE,encoding='utf-8')
 base=ROOT/'common/src/main/java/net/shiroha233/roadweaver/pathlogic/profile'
 files=[base/'ProfileDpSolver.java',base/'ProfilePairTransitions.java',
        ROOT/'common/src/test/java/net/shiroha233/roadweaver/pathlogic/profile/FrozenProfileDpSolver.java']
 subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(work),*[str(p) for p in files],str(source)],check=True,capture_output=True)
 run=subprocess.run([str(JAVA/'java.exe'),'-Xmx512m','-cp',str(work),'net.shiroha233.roadweaver.pathlogic.profile.PairProbe'],check=True,capture_output=True)
 rows=json.loads(run.stdout)
 result=dict(scope='actual solvers; exact full-profile check; synthetic costs; not Minecraft',
             old_base='326742d',old_source_sha256='57faa5974248a8f039201ad7271f94408966fa7d4add7a9cbb0999ed420ba3d1',
             source_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files},samples=rows)
 target=ROOT/'.analysis_tmp/profile-pair-benchmark.json'
 target.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
 for row in rows:
  print(row['limit'],*[round(sorted(v[0] for v in row[key])[3]/1e6,5) for key in ['old_ns_allocated_bytes','new_ns_allocated_bytes']])
if __name__=='__main__':main()
