import dev.c4lam.celeste.module.impl.visual.expansion.HitDisplacementWorld;
import dev.c4lam.celeste.module.impl.visual.expansion.HitDisplacementWorld.*;

public final class HitDisplacementWorldCheck {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static void close(double a, double b, String why) { check(Math.abs(a - b) < 1e-6, why); }
    public static void main(String[] args) {
        Point min = new Point(-.3,0,-.3), max = new Point(.3,1.8,.3);
        Point impact = HitDisplacementWorld.impact(new Point(0,1.62,3), new Point(0,-.08,-1), min,max);
        close(impact.z(),.3,"front surface"); close(impact.y(),1.404,"look pitch defines impact");
        Point silent = HitDisplacementWorld.impact(new Point(3,1.62,0), new Point(-1,-.08,0), min,max);
        close(silent.x(),.3,"silent side aim hits side surface"); close(silent.y(),1.404,"silent pitch");
        Point fallback = HitDisplacementWorld.impact(new Point(3,1.62,0), new Point(1,0,0), min,max);
        close(fallback.x(),.3,"miss falls back to enemy surface");
        Point inside = HitDisplacementWorld.impact(new Point(0,1,0), new Point(0,0,-1), min,max);
        close(inside.z(),-.3,"overlapping target uses exit surface instead of player eye");
        close(inside.y(),1,"point-blank impact height");
        check(HitDisplacementWorld.impact(new Point(Double.NaN,0,0),new Point(0,0,-1),min,max)==null,"reject invalid eye");
        close(HitDisplacementWorld.screen(.6,-.4,0,4).x(),.8,"offcenter horizontal projection");
        close(HitDisplacementWorld.screen(.6,-.4,0,4).y(),.3,"texture Y uses bottom-left");
        check(HitDisplacementWorld.screen(0,0,0,-2)==null,"behind camera hidden");
        check(HitDisplacementWorld.screen(0,0,2,2)==null,"clipped depth hidden");
        check(HitDisplacementWorld.screen(Double.NaN,0,0,2)==null,"nonfinite hidden");
        check(HitDisplacementWorld.screen(1.1,0,0,2).x()>1,"offscreen center is not clamped");
        HitDisplacementWorld animation = new HitDisplacementWorld();
        Anchor anchor = new Anchor(8,impact,impact);
        long start=1_000_000_000L;
        animation.pulse(start,anchor,0x4BD2FF,1);
        var first=animation.sample(start+160_000_000L,a->HitDisplacementWorld.screen(.6,-.4,0,4)).getFirst();
        var f5=animation.sample(start+170_000_000L,a->HitDisplacementWorld.screen(-.7,.2,0,6)).getFirst();
        close(first.x(),.8,"first person projection");close(f5.x(),.15,"same hit reprojects in F5");
        close(f5.y(),.6,"new camera vertical projection");check(f5.radius()>0,"visible hit animates");
        check(animation.sample(start+180_000_000L,a->null).isEmpty(),"hidden target skipped");
        check(animation.size()==1,"temporarily hidden pulse retains lifetime");
        var follow=animation.sample(start+200_000_000L,a->{Point moved=a.offset().add(new Point(1,0,0));
            close(moved.x(),impact.x()+1,"anchor follows target local position");return new Screen(.7f,.4f);});
        close(follow.getFirst().x(),.7,"target motion affects projection");
        var worldFrames=animation.sampleWorld(start+200_000_000L,a->new Point(-1,.4,-5));
        close(worldFrames.getFirst().position().x(),-1,"view X reaches shader unchanged");
        close(worldFrames.getFirst().position().z(),-5,"view depth reaches projection shader");
        close(worldFrames.getFirst().radius(),follow.getFirst().radius(),"world animation matches tested lens lifecycle");
        check(animation.sampleWorld(start+200_000_000L,a->new Point(Double.NaN,0,0)).isEmpty(),"invalid view anchor rejected");
        for(int i=0;i<20;i++) animation.pulse(start+210_000_000L+i,new Anchor(i,impact,impact),0xFF000000+i,1);
        check(animation.size()==4,"bounded recent hits");
        check(animation.sample(start+900_000_000L,a->new Screen(.2f,.3f)).isEmpty(),"expired effects gone");
        check(animation.size()==0,"expired state removed");
        animation.pulse(start,anchor,0,1);animation.reset();check(animation.size()==0,"disable clears state");
        animation.pulse(start,anchor,0,1);animation.sample(start-1,a->new Screen(.5f,.5f));check(animation.size()==0,"clock rewind expires state");
        animation.pulse(start,new Anchor(1,new Point(Double.NaN,0,0),impact),0,1);check(animation.size()==0,"invalid anchor rejected");
        System.out.println("PASS world hit displacement: "+checks+" checks; action-ray impact, silent aim, camera projection, F5 reprojection, local anchor, clipping and lifecycle");
    }
}
