package dev.c4lam.celeste.rotation;
public final class RotationManager {
 private static final RotationManager INSTANCE=new RotationManager();public static RotationManager getInstance(){return INSTANCE;}
 public void release(Object owner){} public boolean permitsAction(Object owner){return true;} public boolean alignForAction(Object owner){return true;}
}
