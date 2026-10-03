package dev.c4lam.celeste.render.font;
import java.awt.Color;
import net.minecraft.client.util.math.MatrixStack;
/** Records actual renderer submissions without starting an OpenGL context. */
public final class Fonts {
    public static int draws;
    public static boolean unavailable;
    public static String text;
    public static float x,y,size;
    public static Color color;
    private static final Face BOLD=new Face();
    public static Face bold(){if(unavailable)throw new IllegalStateException("Synthetic font failure");return BOLD;}
    public static final class Face {
        public float height(float fontSize){return fontSize;}
        public void drawCenteredText(MatrixStack matrix,String content,float center,float top,float fontSize,Color tint){
            draws++;text=content;x=center;y=top;size=fontSize;color=tint;
        }
    }
    private Fonts(){ }
}
