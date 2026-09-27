package net.minecraft.util;
public class ActionResult {
 public static final ActionResult SUCCESS=new Success(SwingSource.CLIENT),CONSUME=new Success(SwingSource.NONE),FAIL=new ActionResult(),PASS=new ActionResult();
 public boolean isAccepted(){return this instanceof Success;}
 public enum SwingSource {CLIENT,SERVER,NONE}
 public static final class Success extends ActionResult {private final SwingSource source;public Success(SwingSource source){this.source=source;}public SwingSource swingSource(){return source;} }
}
