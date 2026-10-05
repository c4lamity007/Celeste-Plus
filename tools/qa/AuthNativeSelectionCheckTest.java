import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Negative semantic-selection fixtures only; no inspected code is executed. */
public final class AuthNativeSelectionCheckTest {
 static int checks;
 static final String SIGNER="dev/c4lam/celeste/auth/DpopProofSigner",BOOT="dev/c4lam/celeste/auth/AuthBootstrap";
 static ClassNode owner(String name){ClassNode n=new ClassNode();n.name=name;return n;}
 static MethodNode proof(int count){return new MethodNode(Opcodes.ACC_PRIVATE,"proof","("+"Ljava/lang/String;".repeat(count)+")Ljava/lang/String;",null,null);}
 static void call(MethodNode m,String o,String name,String desc){m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,o,name,desc,false));}
 static MethodNode builder(){MethodNode m=proof(8);m.instructions.add(new VarInsnNode(Opcodes.ALOAD,7));m.instructions.add(new VarInsnNode(Opcodes.ALOAD,8));for(int i=0;i<8;i++)call(m,"com/google/gson/JsonObject","addProperty","(Ljava/lang/String;Ljava/lang/String;)V");call(m,SIGNER,"target","(Ljava/net/URI;)Ljava/lang/String;");call(m,SIGNER,"sign","([B)[B");return m;}
 static MethodNode continuation(){MethodNode m=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"lambda$authorize$7","()V",null,null);for(String f:new String[]{"sessionCredential","sessionGrant","sessionDeadlineNanos","moduleSession","modulePayloads","incidentScope"})m.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,BOOT,f,"Ljava/lang/Object;"));call(m,"java/util/concurrent/atomic/AtomicBoolean","compareAndSet","(ZZ)Z");return m;}
 static void accepted(String o,MethodNode m){AuthNativeSelectionCheck.body(owner(o),m);checks++;}
 static void rejected(String o,MethodNode m){try{AuthNativeSelectionCheck.body(owner(o),m);throw new AssertionError("Unsafe selection accepted");}catch(IllegalStateException expected){checks++;}}
 public static void main(String[]args){
  accepted(SIGNER,builder());MethodNode p=proof(6);call(p,SIGNER,"proof","()V");rejected(SIGNER,p);
  p=proof(8);call(p,SIGNER,"proof","()V");rejected(SIGNER,p);
  p=builder();p.instructions.remove(p.instructions.getFirst());rejected(SIGNER,p);
  p=builder();p.instructions.remove(p.instructions.get(1));rejected(SIGNER,p);
  p=builder();p.instructions.remove(p.instructions.getLast());rejected(SIGNER,p);
  accepted(BOOT,continuation());p=continuation();p.instructions.remove(p.instructions.get(4));rejected(BOOT,p);
  p=continuation();p.instructions.remove(p.instructions.getLast());rejected(BOOT,p);
  p=continuation();p.instructions.remove(p.instructions.get(5));rejected(BOOT,p);
  p=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"lambda$authorize$6","(Ljava/lang/Throwable;)V",null,null);call(p,BOOT,"lockSession","(Ljava/lang/String;Ljava/lang/Throwable;)V");rejected(BOOT,p);
  System.out.println("PASS semantic-selector-regressions="+checks);
 }
}
