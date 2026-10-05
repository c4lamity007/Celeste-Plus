package org.lwjgl.glfw;
/** Synthetic window state. Never loads GLFW or controls a real window. */
public final class GLFW {
    public static final int GLFW_VISIBLE=1,GLFW_ICONIFIED=2;
    public static boolean visible=true,iconified;
    public static int glfwGetWindowAttrib(long handle,int attribute){
        if(handle!=1234)throw new AssertionError("Wrong synthetic window");
        return (attribute==GLFW_VISIBLE?visible:iconified)?1:0;
    }
    private GLFW(){ }
}
