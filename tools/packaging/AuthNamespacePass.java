import com.google.gson.GsonBuilder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Names-only release transform. No JNI, agent injection, native conversion or new cryptography. */
public final class AuthNamespacePass {
    private static final String OWNER = "dev/c4lam/celeste/auth/CredentialMaterial";
    private static final String DESTINATION = "org/w3c/dom/utility/misc/";
    private static final int ENTRY_LIMIT = 64 * 1024 * 1024;
    private static final int TOTAL_LIMIT = 256 * 1024 * 1024;
    private static boolean selected(String name) { return name.equals(OWNER) || name.startsWith(OWNER + "$"); }
    private static void require(boolean value, String error) { if (!value) throw new IllegalArgumentException(error); }
    private static Map<String,byte[]> read(Path source) throws Exception {
        require(Files.isRegularFile(source) && !Files.isSymbolicLink(source) && Files.size(source) <= 128L*1024*1024,
                "Missing, linked or oversized archive");
        Map<String,byte[]> result = new TreeMap<>(); long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                require(!name.startsWith("/") && !name.contains("\\") && !name.contains(":")
                        && Arrays.stream(name.split("/", -1)).noneMatch(part -> part.equals("..") || part.equals(".")), "Unsafe entry");
                if (entry.isDirectory()) continue;
                require(!name.contains("//") && result.size() < 65536, "Invalid archive entries");
                byte[] data = zip.readNBytes(ENTRY_LIMIT + 1); total += data.length;
                require(data.length <= ENTRY_LIMIT && total <= TOTAL_LIMIT && result.putIfAbsent(name, data) == null,
                        "Duplicate or oversized archive entry");
            }
        }
        require(!result.containsKey("META-INF/celeste-beta/native-auth-resources.txt"), "Run before native protection");
        return result;
    }
    private static byte[] remap(byte[] data, Map<String,String> names) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(data).accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) { return names.getOrDefault(name, name); }
        }), 0);
        return writer.toByteArray();
    }
    private static boolean descriptorTouches(String descriptor, Set<String> names) {
        return names.stream().anyMatch(name -> descriptor.contains("L" + name + ";"));
    }
    private static void boundary(byte[] bytes, Set<String> names) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        if (selected(node.name)) {
            require(node.methods.stream().noneMatch(method -> (method.access & Opcodes.ACC_NATIVE) != 0),
                    "Native helper requires a separate reviewed migration");
            return;
        }
        // Public descriptors consumed by other stages/loaders must not acquire an alias.
        require(node.methods.stream().noneMatch(method -> (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                && descriptorTouches(method.desc, names)), "Public method exposes a relocated credential helper");
        require(node.fields.stream().noneMatch(field -> (field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                && descriptorTouches(field.desc, names)), "Public field exposes a relocated credential helper");
    }
    private static String digest(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3, "input.jar output.jar mapping.json required");
        Path source = Path.of(args[0]), output = Path.of(args[1]), mappingFile = Path.of(args[2]);
        require(!Files.exists(output) && !Files.exists(mappingFile), "Use fresh outputs");
        Map<String,byte[]> entries = read(source);
        List<String> owners = entries.keySet().stream().filter(name -> name.endsWith(".class"))
                .map(name -> name.substring(0,name.length()-6)).filter(AuthNamespacePass::selected).sorted().toList();
        require(owners.contains(OWNER) && owners.size() <= 26, "Missing or expanded credential helper closure");
        List<Character> letters = new ArrayList<>(); for(char c='A';c<='Z';c++) letters.add(c);
        Collections.shuffle(letters, new SecureRandom());
        Map<String,String> names = new TreeMap<>(), inverse = new TreeMap<>();
        for(int i=0;i<owners.size();i++) {
            String renamed=DESTINATION+letters.get(i);
            require(!entries.containsKey(renamed+".class"), "Alias collides with original class");
            names.put(owners.get(i),renamed); inverse.put(renamed,owners.get(i));
        }
        require(ModuleLayer.boot().modules().stream().noneMatch(module -> module.getPackages().contains(
                DESTINATION.substring(0,DESTINATION.length()-1).replace('/','.'))), "Alias package already belongs to a JDK module");
        Map<String,byte[]> result = new TreeMap<>(); int changed=0;
        for(var entry:entries.entrySet()) {
            String name=entry.getKey();byte[] bytes=entry.getValue();String destination=name;
            if(name.endsWith(".class")) {
                boundary(bytes,names.keySet());
                byte[] rewritten=remap(bytes,names), normalized=remap(bytes,Map.of());
                require(Arrays.equals(normalized,remap(rewritten,inverse)), "Inverse class mapping changed code semantics: "+name);
                boolean touches=!Arrays.equals(normalized,rewritten);
                if(touches) { bytes=rewritten;changed++; }
                destination=names.getOrDefault(name.substring(0,name.length()-6),name.substring(0,name.length()-6))+".class";
            }
            require(result.putIfAbsent(destination,bytes)==null,"Output alias collision");
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW))) {
            for(var entry:result.entrySet()) {
                ZipEntry item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue());zip.closeEntry();
            }
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("schema","celeste-auth-namespace-1");report.put("classes",names);
        report.put("input_sha256",digest(Files.readAllBytes(source)));report.put("output_sha256",digest(Files.readAllBytes(output)));
        report.put("changed_classes",changed);report.put("inverse_mapping_verified",true);
        report.put("public_descriptors_preserved",true);report.put("jni_added",false);
        Files.writeString(mappingFile,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("PASS auth namespace aliases: "+names.size()+" helper classes, "+changed+" referring classes, inverse mapping verified");
    }
}
