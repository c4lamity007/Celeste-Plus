import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Offline selector/body preflight. Never initializes or executes the inspected classes. */
public final class AuthNativeSelectionCheck {
 static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
 static boolean calls(MethodNode m,String owner,String name,String desc){
  for(var i:m.instructions)if(i instanceof MethodInsnNode n&&n.owner.equals(owner)&&n.name.equals(name)&&n.desc.equals(desc))return true;return false;
 }
 static void body(ClassNode owner,MethodNode m) {
  require((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0&&m.instructions.size()>0,"Selected input body is unavailable");
  if(owner.name.endsWith("/DpopProofSigner")&&m.name.equals("proof")) {
   Type[] args=Type.getArgumentTypes(m.desc);require(args.length==8,"Selected proof is not the body/evidence-bound claims builder");
   int properties=0;Set<Integer> loads=new HashSet<>();
   for(var i:m.instructions){if(i instanceof MethodInsnNode n&&n.owner.equals("com/google/gson/JsonObject")&&n.name.equals("addProperty"))properties++;
    if(i instanceof VarInsnNode n&&n.getOpcode()==Opcodes.ALOAD)loads.add(n.var);}
   require(properties>=8&&loads.containsAll(Set.of(7,8))&&calls(m,owner.name,"target","(Ljava/net/URI;)Ljava/lang/String;")
    &&calls(m,owner.name,"sign","([B)[B"),"Selected proof forwards to an unprotected builder or omits body/evidence inputs");
  }
  if(owner.name.endsWith("/AuthBootstrap")&&m.name.startsWith("lambda$authorize$")) {
   Set<String> stores=new HashSet<>();
   for(var i:m.instructions)if(i instanceof FieldInsnNode n&&n.getOpcode()==Opcodes.PUTSTATIC&&n.owner.equals(owner.name))stores.add(n.name);
   require(stores.containsAll(Set.of("sessionCredential","sessionGrant","sessionDeadlineNanos","moduleSession","modulePayloads"))
    &&calls(m,"java/util/concurrent/atomic/AtomicBoolean","compareAndSet","(ZZ)Z"),"Selected callback is not the authenticated module/session publication continuation");
  }
 }
 public static void main(String[] args)throws Exception {
  require(args.length==2||(args.length==3&&args[2].equals("--named-runtime")),"class selector-file [--named-runtime] required");
  ClassNode owner=new ClassNode();new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(owner,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
  List<String> selectors=Files.readAllLines(Path.of(args[1]));require(!selectors.isEmpty()&&selectors.size()==new HashSet<>(selectors).size(),"Missing/duplicate selectors");
  if(owner.name.endsWith("/DpopProofSigner"))require(selectors.size()==3&&selectors.stream().anyMatch(s->s.contains("#proof("))
    &&selectors.stream().anyMatch(s->s.contains("#requireBinding("))&&selectors.stream().anyMatch(s->s.contains("#sign(")),"Signer selection coverage changed");
  if(owner.name.endsWith("/AuthBootstrap"))require(selectors.size()==5&&selectors.stream().anyMatch(s->s.contains("#lambda$authorize$"))
    &&selectors.stream().anyMatch(s->s.contains("#authorize("))&&selectors.stream().anyMatch(s->s.contains("#heartbeat("))
    &&selectors.stream().anyMatch(s->s.contains("#superviseHeartbeat("))&&selectors.stream().anyMatch(s->s.contains("#lockSession(")),"Bootstrap selection coverage changed");
  for(String original:selectors){String selector=args.length==3?original.replace("Lnet/minecraft/class_310;","Lnet/minecraft/client/MinecraftClient;"):original;
   require(selector.startsWith(owner.name+"#"),"Selector owner differs");
   var matches=owner.methods.stream().filter(m->selector.equals(owner.name+"#"+m.name+m.desc)).toList();
   require(matches.size()==1,"Selected body absent: "+original);body(owner,matches.getFirst());}
  System.out.println("PASS native-selector-bodies="+selectors.size()+" owner="+owner.name+"; offline code inspection only");
 }
}
