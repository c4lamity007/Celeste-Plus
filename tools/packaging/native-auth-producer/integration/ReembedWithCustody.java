import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
/** Isolated, offline archive finalization. No code execution or authorization inference. */
public final class ReembedWithCustody {
 static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static void require(boolean value,String error)throws IOException{if(!value)throw new IOException(error);}
 static ClassNode node(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
 public static void main(String[] args)throws Exception {
  require(args.length==7,"inputJar symbolsJson originalDll finalDll expectedFinalSha outputJar templateClass required");
  Path input=Path.of(args[0]),symbols=Path.of(args[1]),output=Path.of(args[5]);
  require(!Files.exists(output),"Output must be fresh");
  byte[] original=Files.readAllBytes(Path.of(args[2])),replacement=Files.readAllBytes(Path.of(args[3]));
  require(args[4].matches("[0-9a-f]{64}")&&hash(replacement).equals(args[4]),"Final native digest does not match explicit approved input");
  require(replacement.length>4096&&replacement.length<=64*1024*1024&&replacement[0]=='M'&&replacement[1]=='Z',"Final native input is not a bounded PE candidate");
  JsonObject map=JsonParser.parseString(Files.readString(symbols)).getAsJsonObject();
  String resource=map.get("resource").getAsString(),owner=map.get("loader").getAsString();
  require(resource.startsWith("META-INF/")&&!resource.contains("..")&&!owner.contains(".."),"Invalid archive mapping");
  LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
  try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(input))){ZipEntry item;while((item=zip.getNextEntry())!=null){
   require(!entries.containsKey(item.getName()),"Duplicate archive entry");if(item.isDirectory())continue;
   require(!item.getName().matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)"),"Signed archive must be finalized before signing");
   entries.put(item.getName(),zip.readAllBytes());
  }}
  require(Arrays.equals(entries.get(resource),original),"Original DLL does not match the mapped source resource");
  require(entries.containsKey(owner+".class"),"Missing mapped loader");
  String inputLoaderHash=hash(entries.get(owner+".class"));
  ClassNode loader=node(entries.get(owner+".class"));require(loader.name.equals(owner),"Loader identity mismatch");
  List<MethodNode> candidates=loader.methods.stream().filter(m->m.desc.equals("(Ljava/lang/Class;Ljava/lang/String;)V")&&(m.access&Opcodes.ACC_STATIC)!=0).toList();
  require(candidates.size()==1,"Ambiguous native resource loader method");
  List<FieldNode> fields=loader.fields.stream().filter(f->f.desc.equals("Ljava/lang/String;")&&(f.access&(Opcodes.ACC_STATIC|Opcodes.ACC_VOLATILE))==(Opcodes.ACC_STATIC|Opcodes.ACC_VOLATILE)).toList();
  require(fields.size()==1,"Ambiguous native loaded-resource state");
  ClassNode template=node(Files.readAllBytes(Path.of(args[6])));MethodNode source=template.methods.stream().filter(m->m.name.equals("load")).findFirst().orElseThrow();
  MethodNode method=new MethodNode(Opcodes.ASM9,source.access,candidates.get(0).name,source.desc,source.signature,source.exceptions.toArray(String[]::new));source.accept(method);
  method.name=candidates.get(0).name;method.access=candidates.get(0).access;int pins=0,bridge=0,loads=0;
  for(AbstractInsnNode insn:method.instructions){
   if(insn instanceof LdcInsnNode ldc&&"EXPECTED_APPROVED_NATIVE_SHA256".equals(ldc.cst)){ldc.cst=args[4];pins++;}
   if(insn instanceof FieldInsnNode field&&field.owner.equals(template.name)) {require(field.name.equals("loadedResource"),"Unexpected template field");field.owner=owner;field.name=fields.get(0).name;}
   if(insn instanceof MethodInsnNode call){if(call.owner.equals("dev/celestebeta/host/AuthNativeBridge")&&call.name.equals("stage"))bridge++;if(call.owner.equals("java/lang/System")&&call.name.equals("load"))loads++;}
  }
  require(pins==1&&bridge==1&&loads==1,"Template must bind one final digest, parent custody call and child-owned System.load");
  loader.methods.set(loader.methods.indexOf(candidates.get(0)),method);ClassWriter writer=new ClassWriter(0);loader.accept(writer);
  entries.put(owner+".class",writer.toByteArray());entries.put(resource,replacement);
  try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW))){for(var e:entries.entrySet()){ZipEntry item=new ZipEntry(e.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(e.getValue());zip.closeEntry();}}
  JsonObject record=new JsonObject();record.addProperty("schema","celeste-native-auth-custody-1");record.addProperty("scope","offline resource replacement and parent custody wiring; not a VMP protection approval");record.addProperty("input_jar_sha256",hash(Files.readAllBytes(input)));record.addProperty("output_jar_sha256",hash(Files.readAllBytes(output)));record.addProperty("symbols_sha256",hash(Files.readAllBytes(symbols)));record.addProperty("original_dll_sha256",hash(original));record.addProperty("final_dll_sha256",hash(replacement));record.addProperty("loader",owner);record.addProperty("resource",resource);record.addProperty("changed_entries",2);record.addProperty("input_loader_sha256",inputLoaderHash);record.addProperty("loader_sha256",hash(entries.get(owner+".class")));record.addProperty("template_sha256",hash(Files.readAllBytes(Path.of(args[6]))));
  Files.writeString(Path.of(output+".custody.json"),new GsonBuilder().setPrettyPrinting().create().toJson(record)+"\n",StandardOpenOption.CREATE_NEW);
  System.out.println("PASS: exact mapped resource and child native-loader custody patched; archive is still unsigned prototype");
 }
}
