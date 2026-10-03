package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;

/** Auth-owned overlay submitted at the unconditional GUI flush, including menus and hidden ordinary HUD. */
public final class SecurityWarningRenderer {
    private static final Color RED=new Color(255,48,65,255);
    private SecurityWarningRenderer(){ }

    /** Returns the exact controller that drew this frame, never performs auth, hashing or network work. */
    public static Object beforeFlush() {
        try { return submitWarning(); }
        catch(RuntimeException|LinkageError unavailable) { return null; }
    }

    private static Object submitWarning() {
        SecurityIncidentController incident=AuthBootstrap.warningController();
        if(incident==null||!incident.showWarning())return null;
        MinecraftClient client=MinecraftClient.getInstance();
        var window=client.getWindow();long handle=window.getHandle();
        if(!client.isWindowFocused()||window.getFramebufferWidth()<=0||window.getFramebufferHeight()<=0
                ||GLFW.glfwGetWindowAttrib(handle,GLFW.GLFW_VISIBLE)==0
                ||GLFW.glfwGetWindowAttrib(handle,GLFW.GLFW_ICONIFIED)!=0)return null;
        float size=22f;
        Fonts.bold().drawCenteredText(new MatrixStack(),"Stop.",window.getScaledWidth()*.5f,
            (window.getScaledHeight()-Fonts.bold().height(size))*.5f,size,RED);
        return incident;
    }

    /** Count visible duration only after successful actual batch rendering. */
    public static void afterFlush(Object frame) {
        if(frame instanceof SecurityIncidentController incident&&AuthBootstrap.warningController()==incident)
            incident.renderedWarningFrame();
    }
}
