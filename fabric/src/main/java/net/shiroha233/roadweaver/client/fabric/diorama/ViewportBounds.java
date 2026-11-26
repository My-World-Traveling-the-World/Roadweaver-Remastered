/* 文件职责：反算倾斜视口覆盖的道路查询范围。 */
package net.shiroha233.roadweaver.client.fabric.diorama;

/** 倾斜地图的可见范围必须包含高度上下界；只按Y=64反算会漏掉高桥和高山道路。 */
public record ViewportBounds(int minX,int minZ,int maxX,int maxZ) {
    public static ViewportBounds of(double centerU,double centerV,double width,double height,
                                    int minY,int maxY,int turn,double sin,double cos) {
        double lowX=Double.POSITIVE_INFINITY,lowZ=lowX,highX=Double.NEGATIVE_INFINITY,highZ=highX;
        for(double u:new double[]{centerU-width/2,centerU+width/2})
            for(double v:new double[]{centerV-height/2,centerV+height/2})
                for(int y:new int[]{minY,maxY}) {
                    double z=(v+y*cos)/sin;
                    double xw=switch(Math.floorMod(turn,4)){case 1->-z;case 2->-u;case 3->z;default->u;};
                    double zw=switch(Math.floorMod(turn,4)){case 1->u;case 2->-z;case 3->-u;default->z;};
                    lowX=Math.min(lowX,xw);highX=Math.max(highX,xw);lowZ=Math.min(lowZ,zw);highZ=Math.max(highZ,zw);
                }
        return new ViewportBounds((int)Math.floor(lowX)-2,(int)Math.floor(lowZ)-2,(int)Math.ceil(highX)+2,(int)Math.ceil(highZ)+2);
    }
}
