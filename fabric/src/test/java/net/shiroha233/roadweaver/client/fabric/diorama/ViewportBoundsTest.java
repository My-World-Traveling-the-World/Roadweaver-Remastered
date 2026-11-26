package net.shiroha233.roadweaver.client.fabric.diorama;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 独立正向投影对拍反算窗口，高程和四个朝向都必须覆盖，避免夹具只验证Y=64。 */
class ViewportBoundsTest {
    @Test void allFacingAndHeightCornersStayInsideQuery() {
        for(int turn=0;turn<4;turn++)for(double pitch:new double[]{25,45,70}) {
            double sin=Math.sin(Math.toRadians(pitch)),cos=Math.cos(Math.toRadians(pitch));
            var bounds=ViewportBounds.of(12,-30,800,600,-64,2048,turn,sin,cos);
            int visible=0;
            for(int x=-2500;x<=2500;x+=100)for(int z=-2500;z<=2500;z+=100)for(int y:new int[]{-64,64,1024,2048}){
                double u=switch(turn){case 1->z;case 2->-x;case 3->-z;default->x;};
                double depth=switch(turn){case 1->-x;case 2->-z;case 3->x;default->z;};
                double v=depth*sin-y*cos;
                if(Math.abs(u-12)<=400&&Math.abs(v+30)<=300){
                    visible++;
                    assertTrue(x>=bounds.minX()&&x<=bounds.maxX()&&z>=bounds.minZ()&&z<=bounds.maxZ(),turn+" "+pitch+" "+x+","+y+","+z);
                }
            }
            assertTrue(visible>0,"每个朝向与俯仰都必须真正命中可见点");
        }
    }
}
