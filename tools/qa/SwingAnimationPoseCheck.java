package dev.c4lam.celeste.module.impl.visual.expansion;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class SwingAnimationPoseCheck {
    private static int checks;
    private static void require(boolean condition,String message) {
        checks++; if(!condition) throw new AssertionError(message);
    }
    private static void near(Vector3f a,Vector3f b,String message) { require(a.distance(b)<2e-5,message); }
    public static void main(String[] args) {
        for(String style:new String[]{"Smooth","Slice","Sweep","Thrust","Spin"}) for(boolean right:new boolean[]{true,false}) {
            Vector3f grip=new Vector3f(right?.56F:-.56F,-.52F,-.72F);
            for(double progress:new double[]{-1,0,1,2,Double.NaN})
                require(SwingAnimationPose.transform(style,right,progress,40,true).equals(new Matrix4f()),"Idle/invalid motion is identity");
            for(int i=0;i<=100;i++) {
                double progress=i/100.0;
                Matrix4f swing=SwingAnimationPose.transform(style,right,progress,40,false);
                near(swing.transformPosition(new Vector3f(grip)),grip,"Rotation stays about grip with position disabled");
                require(swing.isFinite(),"Finite transform");
                near(SwingAnimationPose.transform(style,right,progress,0,true).transformPosition(new Vector3f(1,2,3)),new Vector3f(1,2,3),"Zero strength unchanged");
                for(double x:new double[]{.15,.50,.85}) {
                    Matrix4f base=HandModelsPose.transform(right,x,.72,16.0/9,70,1.2);
                    Vector3f before=base.transformPosition(new Vector3f(grip));
                    Vector3f after=new Matrix4f(base).mul(swing).transformPosition(new Vector3f(grip));
                    near(after,before,"Swing rotation preserves configured Hand Models anchor");
                }
            }
            Matrix4f placed=SwingAnimationPose.transform(style,right,.45,40,true);
            require(placed.transformPosition(new Vector3f(grip)).distance(grip)>.005,"Affect position adds local movement");
        }
        for(String style:new String[]{"Smooth","Slice","Sweep","Thrust","Spin"}) {
            Vector3f a=SwingAnimationPose.transform(style,true,.4,40,true).transformPosition(new Vector3f(.56F,-.52F,-.72F));
            Vector3f b=SwingAnimationPose.transform(style,false,.4,40,true).transformPosition(new Vector3f(-.56F,-.52F,-.72F));
            near(new Vector3f(-a.x,a.y,a.z),b,"Physical arms mirror position");
        }
        System.out.println("PASS SwingAnimationPose: "+checks+" checks");
    }
}
