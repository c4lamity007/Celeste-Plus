package dev.celestebeta.bootstrap.external;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Library cleanup authority is account-private and separate from all authentication keys. */
final class SupportFileCustodian implements AutoCloseable {
    static final String RECEIPT = ".celeste-owned-support.json";
    private static final String KEY_DIRECTORY = ".celeste-support-cleanup";
    private final Path scope;
    private final boolean processRoots;
    private final byte[] key;

    SupportFileCustodian(Path root) throws IOException {
        checked(root,true);
        processRoots = root.getFileName().toString().matches("[1-9][0-9]{0,19}-[0-9]{1,20}");
        scope = processRoots ? root.getParent() : root;
        checked(scope,true);
        Path keyDirectory=scope.resolve(KEY_DIRECTORY);
        try { Files.createDirectory(keyDirectory); restrict(keyDirectory,true); }
        catch(java.nio.file.FileAlreadyExistsException existing) { assertPrivate(keyDirectory,true); }
        Path path=keyDirectory.resolve("owner.key");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] created=new byte[32];new SecureRandom().nextBytes(created);
            try { Files.write(path,created,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);restrict(path,false); }
            catch(java.nio.file.FileAlreadyExistsException race) { /* Another authorized launch created the key. */ }
            finally { Arrays.fill(created,(byte)0); }
        }
        assertPrivate(path,false);
        if(Files.size(path)!=32) throw new IOException("Invalid support cleanup authority");
        key=Files.readAllBytes(path);
        if(key.length!=32) throw new IOException("Support cleanup authority changed");
    }
    @Override public void close() { Arrays.fill(key,(byte)0); }

    byte[] receipt(Path directory, Map<String,String> expected) throws IOException {
        ProcessHandle process=ProcessHandle.current();
        long start=process.info().startInstant().orElseThrow(()->new IOException("Process creation identity unavailable")).toEpochMilli();
        return receipt(directory,expected,process.pid(),start);
    }
    // Package seam for controlled child-process lifecycle fixtures; production always uses current().
    byte[] receipt(Path directory, Map<String,String> expected,long pid,long started) throws IOException {
        if(pid<=0||started<=0||expected.isEmpty()||expected.size()>16)throw new IOException("Invalid support ownership");
        JsonObject value=new JsonObject();value.addProperty("schema","celeste-support-cleanup-1");
        value.addProperty("directory",directory.toAbsolutePath().normalize().toString());
        value.add("identity",identity(directory,true));value.addProperty("pid",pid);value.addProperty("started",started);
        JsonObject files=new JsonObject();
        for(var entry:expected.entrySet()) {
            if(!entry.getKey().matches("[A-Za-z0-9_.+-]+\\.jar")||entry.getKey().contains(".."))throw new IOException("Invalid support cleanup filename");
            Path file=directory.resolve(entry.getKey());JsonObject item=identity(file,false);
            item.addProperty("sha256",entry.getValue());item.addProperty("size",Files.size(file));files.add(entry.getKey(),item);
        }
        value.add("files",files);
        byte[] body=value.toString().getBytes(StandardCharsets.UTF_8);
        JsonObject wrapper=new JsonObject();wrapper.addProperty("body",Base64.getUrlEncoder().withoutPadding().encodeToString(body));
        wrapper.addProperty("mac",Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body)));
        return wrapper.toString().getBytes(StandardCharsets.UTF_8);
    }
    /** Bounded scan, fixed direct names only; malformed, active, changed or unowned paths are preserved. */
    int sweep() throws IOException {
        int cleaned=0;
        List<Path> roots;
        if(processRoots) {
            try(var entries=Files.list(scope)){roots=entries.filter(p->p.getFileName().toString().matches("[1-9][0-9]{0,19}-[0-9]{1,20}")).limit(256).toList();}
        } else roots=List.of(scope);
        for(Path root:roots) {
            try {
                checked(root,true);
                try(var entries=Files.list(root)) {
                    for(Path directory:entries.filter(p->p.getFileName().toString().startsWith("celeste-beta-support-")).limit(128).toList()) {
                        try { if(retire(directory))cleaned++; }catch(IOException|RuntimeException ignored){ /* Unknown ownership is never a deletion instruction. */ }
                    }
                }
            }catch(IOException ignored){ }
        }
        return cleaned;
    }
    private boolean retire(Path directory)throws IOException {
        assertPrivate(directory,true);
        Path receipt=directory.resolve(RECEIPT);checked(receipt,false);
        if(Files.size(receipt)>131072)throw new IOException("Oversized support receipt");
        byte[] stored=Files.readAllBytes(receipt);
        if(stored.length>131072)throw new IOException("Changed support receipt");
        JsonObject wrapper=JsonParser.parseString(new String(stored,StandardCharsets.UTF_8)).getAsJsonObject();
        if(!wrapper.keySet().equals(Set.of("body","mac")))throw new IOException("Invalid support receipt");
        byte[] body=decode(wrapper.get("body").getAsString());
        if(!MessageDigest.isEqual(mac(body),decode(wrapper.get("mac").getAsString())))throw new IOException("Unowned support receipt");
        JsonObject value=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
        if(!value.keySet().equals(Set.of("schema","directory","identity","pid","started","files"))
            ||!value.get("schema").getAsString().equals("celeste-support-cleanup-1")
            ||!value.get("directory").getAsString().equals(directory.toAbsolutePath().normalize().toString())
            ||!value.getAsJsonObject("identity").equals(identity(directory,true)))throw new IOException("Support directory ownership changed");
        long pid=value.get("pid").getAsLong(), start=value.get("started").getAsLong();
        if(pid<=0||start<=0)throw new IOException("Invalid owner process");
        var owner=ProcessHandle.of(pid);
        if(owner.isPresent() && owner.get().isAlive()) {
            var actual=owner.get().info().startInstant();
            if(actual.isEmpty()||actual.get().toEpochMilli()==start)return false;
        }
        JsonObject files=value.getAsJsonObject("files");
        if(files.isEmpty()||files.size()>16)throw new IOException("Invalid support file count");
        Set<String> names=new HashSet<>(files.keySet());names.add(RECEIPT);
        try(var entries=Files.list(directory)) {
            if(!names.containsAll(entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet())))throw new IOException("Unowned support entries");
        }
        for(var entry:files.entrySet()) if(Files.exists(directory.resolve(entry.getKey()),LinkOption.NOFOLLOW_LINKS)) verifyFile(directory,entry.getKey(),entry.getValue().getAsJsonObject());
        // All files, owner process and directory identity were verified before the first deletion.
        for(var entry:files.entrySet()) {
            if(!Files.exists(directory.resolve(entry.getKey()),LinkOption.NOFOLLOW_LINKS))continue;
            if(!value.getAsJsonObject("identity").equals(identity(directory,true)))throw new IOException("Support directory replaced");
            verifyFile(directory,entry.getKey(),entry.getValue().getAsJsonObject());Files.delete(directory.resolve(entry.getKey()));
        }
        if(!Arrays.equals(stored,Files.readAllBytes(receipt)))throw new IOException("Support receipt changed");
        Files.delete(receipt);Files.delete(directory);return true;
    }
    private static void verifyFile(Path directory,String name,JsonObject expected)throws IOException {
        if(!name.matches("[A-Za-z0-9_.+-]+\\.jar")||name.contains("..")||!expected.keySet().equals(Set.of("key","created","modified","size","sha256")))throw new IOException("Invalid owned file");
        Path file=directory.resolve(name);JsonObject actual=identity(file,false);
        if(!actual.get("key").equals(expected.get("key"))||!actual.get("created").equals(expected.get("created"))||!actual.get("modified").equals(expected.get("modified"))
            ||Files.size(file)>16*1024*1024||Files.size(file)!=expected.get("size").getAsLong()
            ||!VerifiedBootstrapLoader.hash(Files.readAllBytes(file)).equals(expected.get("sha256").getAsString()))throw new IOException("Support file changed");
    }
    private static byte[] decode(String value)throws IOException {
        if(!value.matches("[A-Za-z0-9_-]+"))throw new IOException("Invalid support receipt encoding");
        byte[] bytes=Base64.getUrlDecoder().decode(value);
        if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))throw new IOException("Noncanonical support receipt encoding");
        return bytes;
    }
    private byte[] mac(byte[] value)throws IOException {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value);}
        catch(GeneralSecurityException error){throw new IOException("Support cleanup verification unavailable",error);}
    }
    private static BasicFileAttributes checked(Path path,boolean directory)throws IOException {
        BasicFileAttributes value=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(value.isSymbolicLink()||value.isOther()||(directory?!value.isDirectory():!value.isRegularFile())
            ||!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException("Unsafe support path");
        return value;
    }
    private static JsonObject identity(Path path,boolean directory)throws IOException {
        var attributes=checked(path,directory);
        if(attributes.fileKey()==null && attributes.creationTime().toMillis()==0)throw new IOException("Support file identity unavailable");
        JsonObject value=new JsonObject();value.addProperty("key",String.valueOf(attributes.fileKey()));value.addProperty("created",attributes.creationTime().toString());if(!directory)value.addProperty("modified",attributes.lastModifiedTime().toString());return value;
    }
    private static java.nio.file.attribute.UserPrincipal currentOwner(Path path)throws IOException {
        String name=System.getProperty("user.name");
        if(System.getProperty("os.name", "").startsWith("Windows")) {
            var account=new com.sun.security.auth.module.NTSystem();
            name=account.getDomain()+"\\"+account.getName();
        }
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }
    static void restrict(Path path,boolean directory)throws IOException {
        checked(path,directory);
        var current=currentOwner(path);
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(current))Files.setOwner(path,current);
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null)posix.setPermissions(directory?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE):Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(acl==null)throw new IOException("Private support permissions unavailable");
            var owner=Files.getOwner(path,LinkOption.NOFOLLOW_LINKS);
            var builder=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class));
            if(directory)builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(builder.build()));
        }
        assertPrivate(path,directory);
    }
    static void assertPrivate(Path path,boolean directory)throws IOException {
        checked(path,directory);
        var current=currentOwner(path);
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(current))throw new IOException("Support belongs to another account");
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) {
            if(posix.readAttributes().permissions().stream().anyMatch(p->!p.name().startsWith("OWNER_")))throw new IOException("Support permissions are public");
        } else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(acl==null||acl.getAcl().stream().anyMatch(e->e.type()==AclEntryType.ALLOW&&!e.principal().equals(current)))throw new IOException("Support permissions are public");
        }
    }
}
