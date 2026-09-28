package dev.c4lam.celeste.module.impl.visual.expansion;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

public final class SwingAnimationPoseCheck {
    private static int checks;
    private static void require(boolean condition,String message) {
        checks++; if(!condition) throw new AssertionError(message);
    }
    private static void near(Vector3f a,Vector3f b,String message) { require(a.distance(b)<2e-5,message); }
    private static double pixelDistance(Vector3f a,Vector3f b) {
        double projection = 540.0 / Math.tan(Math.toRadians(35));
        return Math.hypot((a.x/-a.z-b.x/-b.z)*projection,(a.y/-a.z-b.y/-b.z)*projection);
    }
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
        for (String style : new String[]{"Smooth","Slice","Sweep","Thrust","Spin"}) {
            for (boolean right : new boolean[]{true,false}) {
                Vector3f grip = new Vector3f(right ? .56F : -.56F, -.52F, -.72F);
                Vector3f movement = SwingAnimationPose.position(style,right,.45,40);
                require(movement.length() > .08, "Default positional swing must create visible travel");
                for (double x : new double[]{.15,.5,.85}) for (double scale : new double[]{.65,1,1.7}) {
                    for (boolean correction : new boolean[]{false,true}) {
                        Matrix4f placement = HandModelsPose.transform(right,x,.72,16.0/9,70,scale,
                            correction ? new Quaternionf().rotationY((float)Math.PI*.5F) : null,correction);
                        Matrix4f saved = new Matrix4f(placement);
                        Matrix4f disabled = SwingAnimationPose.compose(placement,style,right,.45,40,false);
                        Matrix4f enabled = SwingAnimationPose.compose(placement,style,right,.45,40,true);
                        Vector3f before = disabled.transformPosition(new Vector3f(grip));
                        Vector3f after = enabled.transformPosition(new Vector3f(grip));
                        near(new Vector3f(after).sub(before),movement,
                            "Hand Models facing and scale must not redirect or shrink position animation");
                        require(pixelDistance(before,after)>12,
                            "Position toggle must visibly move configured grip, including center and both sides");
                        near(disabled.transformDirection(new Vector3f(1,2,3)),enabled.transformDirection(new Vector3f(1,2,3)),
                            "Position toggle preserves preset rotation and configured size");
                        require(placement.equals(saved),"Composition never mutates configured Hand Models matrix");
                        for(double idle : new double[]{0,1,Double.NaN}) {
                            require(SwingAnimationPose.compose(placement,style,right,idle,40,true).equals(placement),
                                "Idle/invalid swing restores exact configured placement");
                        }
                        require(SwingAnimationPose.compose(placement,style,right,.45,0,true).equals(placement),
                            "Zero strength preserves exact configured placement");
                    }
                }
                Matrix4f on = SwingAnimationPose.compose(new Matrix4f(),style,right,.45,40,true);
                Matrix4f off = SwingAnimationPose.compose(new Matrix4f(),style,right,.45,40,false);
                Vector3f before = off.transformPosition(new Vector3f(grip));
                Vector3f after = on.transformPosition(new Vector3f(grip));
                near(new Vector3f(after).sub(before),movement,"Position toggle works without Hand Models");
                require(pixelDistance(before,after)>12,"Position toggle remains visible without Hand Models");
            }
        }
        System.out.println("PASS SwingAnimationPose: "+checks+" checks");
    }
}
