package dev.c4lam.celeste.beta.memory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

/**
 * One verified module's disposable definition domain. It is not a security
 * sandbox; disabling removes host references, and actual unloading is JVM-owned.
 */
public final class ModuleMemoryClassLoader extends ClassLoader implements AutoCloseable {
    private static final int MAX_JAR = 32 * 1024 * 1024, MAX_ENTRY = 8 * 1024 * 1024;
    private static final long MAX_EXPANDED = 64L * 1024 * 1024;
    private final Map<String, byte[]> entries;
    private final Set<String> classes;
    private final String expectedEntriesDigest;
    private final ResourceAccess resourceAccess=new ResourceAccess();
    private volatile boolean closed;
    private volatile boolean closing;
    private Thread verifyingThread;

    private ModuleMemoryClassLoader(ClassLoader parent, Map<String, byte[]> entries, Set<String> classes, String expectedEntriesDigest) {
        super(parent); this.entries=entries; this.classes=Set.copyOf(classes);resourceAccess.owner=this;
        this.expectedEntriesDigest=expectedEntriesDigest;
    }
    /** Verifies the complete executable jar and every expanded entry before any definition. */
    public static ModuleMemoryClassLoader open(ClassLoader parent, byte[] jar, String jarDigest,
                                               String entriesDigest) throws IOException, GeneralSecurityException {
        if (parent==null || jar==null || jar.length==0 || jar.length>MAX_JAR
                || !digest(jar).equals(jarDigest)) throw new GeneralSecurityException("Module jar integrity mismatch");
        Map<String,byte[]> entries=new TreeMap<>();
        Set<String> classes=new java.util.HashSet<>();
        boolean complete=false;
        try (ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(jar),StandardCharsets.UTF_8)) {
            long expanded=0;
            for (var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                String name=entry.getName();
                if (entries.size()>=10_000 || !safe(name) || entries.containsKey(name))
                    throw new IOException("Invalid module archive entry");
                byte[] content=readEntry(zip,entry.getSize(),Math.min(MAX_ENTRY,MAX_EXPANDED-expanded));
                expanded+=content.length;
                if (content.length>MAX_ENTRY || expanded>MAX_EXPANDED || entry.isDirectory() && content.length!=0) {
                    Arrays.fill(content,(byte)0);throw new IOException("Module archive exceeds bounds");
                }
                entries.put(name,content);
                if (name.endsWith(".class")) {
                    String binary=name.substring(0,name.length()-6).replace('/','.');
                    if (!binary.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") || reserved(binary))
                        throw new IOException("Module cannot define resident or platform types");
                    classes.add(binary);
                }
                String lower=name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || name.startsWith("META-INF/versions/"))
                    throw new IOException("Native and multi-release module entries are unsupported");
                zip.closeEntry();
            }
            if (classes.isEmpty() || !entryDigest(entries).equals(entriesDigest))
                throw new GeneralSecurityException("Module entry inventory mismatch");
            complete=true;
            // Preserve the already verified canonical order for recurring integrity
            // scans while retaining constant-time class/resource lookup.
            return new ModuleMemoryClassLoader(parent,new LinkedHashMap<>(entries),classes,entriesDigest);
        } finally { if (!complete) entries.values().forEach(value->Arrays.fill(value,(byte)0)); }
    }
    /** Release ZIPs declare sizes, so read directly into the final owned entry buffer. */
    private static byte[] readEntry(ZipInputStream zip,long declared,long maximum) throws IOException {
        if(maximum<0 || declared>maximum)throw new IOException("Module archive exceeds bounds");
        if(declared>=0){
            byte[] content=new byte[(int)declared];boolean complete=false;
            try{
                // The extra EOF read also forces ZipInputStream's length/CRC validation.
                if(zip.readNBytes(content,0,content.length)!=content.length || zip.read()!=-1)
                    throw new IOException("Module archive entry size mismatch");
                complete=true;return content;
            }finally{if(!complete)Arrays.fill(content,(byte)0);}
        }
        // Streaming ZIPs may omit local-header sizes. Own every staging buffer so
        // partial, rejected and successfully copied plaintext can all be wiped.
        java.util.List<byte[]> chunks=new java.util.ArrayList<>();int total=0;
        try{
            while(true){
                byte[] chunk=new byte[(int)Math.min(8192,maximum+1-total)];chunks.add(chunk);
                int read=zip.readNBytes(chunk,0,chunk.length);total+=read;
                if(total>maximum)throw new IOException("Module archive exceeds bounds");
                if(read<chunk.length)break;
            }
            byte[] content=new byte[total];int offset=0;
            for(byte[] chunk:chunks){int length=Math.min(chunk.length,total-offset);System.arraycopy(chunk,0,content,offset,length);offset+=length;}
            return content;
        }finally{chunks.forEach(chunk->Arrays.fill(chunk,(byte)0));}
    }
    private static boolean reserved(String binary) {
        return binary.startsWith("java.") || binary.startsWith("javax.") || binary.startsWith("jdk.")
            || binary.startsWith("sun.") || binary.startsWith("com.sun.") || binary.startsWith("net.minecraft.")
            || binary.startsWith("net.fabricmc.") || binary.startsWith("org.lwjgl.")
            || binary.startsWith("dev.celestebeta.host.") || binary.startsWith("dev.c4lam.celeste.auth.")
            || binary.startsWith("dev.c4lam.celeste.beta.") || binary.startsWith("dev.c4lam.celeste.module.delivery.")
            || Set.of("dev.c4lam.celeste.module.ModuleFeature","dev.c4lam.celeste.module.ModuleContext",
                      "dev.c4lam.celeste.module.ModuleManager").contains(binary);
    }
    private static boolean safe(String name) {
        return !name.isEmpty() && name.length()<=1024 && !name.startsWith("/") && !name.contains("\\")
            && !name.contains(":") && !name.contains("//") && !name.chars().anyMatch(c->c<32)
            && Arrays.stream(name.split("/",-1)).noneMatch(part->part.equals(".") || part.equals(".."));
    }
    private static String digest(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String entryDigest(Map<String,byte[]> entries) throws GeneralSecurityException {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        ByteBuffer prefix=ByteBuffer.allocate(8);
        for (var entry:entries.entrySet()) {
            byte[] name=entry.getKey().getBytes(StandardCharsets.UTF_8),bytes=entry.getValue();
            prefix.putInt(0,name.length);hash.update(prefix.array(),0,4);hash.update(name);
            prefix.putLong(0,bytes.length);hash.update(prefix.array());hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    @Override protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
        if (closed || closing) throw new ClassNotFoundException("Module loader retired");
        Class<?> loaded=findLoadedClass(name);
        if (loaded==null) loaded=classes.contains(name) ? findClass(name) : super.loadClass(name,false);
        if (resolve) resolveClass(loaded);
        return loaded;
    }
    @Override protected synchronized Class<?> findClass(String name) throws ClassNotFoundException {
        if (closed || closing) throw new ClassNotFoundException("Module loader retired");
        byte[] bytes=entries.get(name.replace('.','/')+".class");
        if (bytes==null) throw new ClassNotFoundException(name);
        return defineClass(name,bytes,0,bytes.length);
    }
    @Override public synchronized InputStream getResourceAsStream(String name) {
        if (closed || closing) return null;
        if (!entries.containsKey(name)) return super.getResourceAsStream(name);
        return new ResourceStream(resourceAccess,name);
    }
    @Override public synchronized java.net.URL getResource(String name) {
        if (closed || closing) return null;
        if (!entries.containsKey(name)) return super.getResource(name);
        try {
            return java.net.URL.of(java.net.URI.create("celeste-module:/"+java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8))),new ResourceHandler(resourceAccess,name));
        } catch(java.net.MalformedURLException invalid) { throw new IllegalStateException(invalid); }
    }
    @Override public synchronized java.util.Enumeration<java.net.URL> getResources(String name) throws IOException {
        if (closed || closing) return java.util.Collections.emptyEnumeration();
        if (entries.containsKey(name)) return java.util.Collections.enumeration(java.util.List.of(getResource(name)));
        return super.getResources(name);
    }
    /** Retained streams and URLs hold this revocable indirection, not the classloader. */
    private static final class ResourceAccess {
        volatile ModuleMemoryClassLoader owner;
        ModuleMemoryClassLoader current() throws IOException {
            ModuleMemoryClassLoader value=owner;
            if(value==null || value.isClosed())throw new IOException("Module resource retired");
            return value;
        }
    }
    private static final class ResourceStream extends InputStream {
        private final ResourceAccess access;
        private final String name;
        private int offset;
        private boolean ended;
        ResourceStream(ResourceAccess access,String name){this.access=access;this.name=name;}
        @Override public int read() throws IOException {
            ModuleMemoryClassLoader loader=access.current();
            synchronized(loader) {
                byte[] bytes=current(loader);
                return offset==bytes.length?-1:bytes[offset++]&255;
            }
        }
        @Override public int read(byte[] out,int start,int length) throws IOException {
            java.util.Objects.checkFromIndexSize(start,length,out.length);
            ModuleMemoryClassLoader loader=access.current();
            synchronized(loader) {
                byte[] bytes=current(loader);
                if(length==0)return 0;
                if(offset==bytes.length)return -1;
                int count=Math.min(length,bytes.length-offset);System.arraycopy(bytes,offset,out,start,count);offset+=count;return count;
            }
        }
        private byte[] current(ModuleMemoryClassLoader loader) throws IOException {
            if(loader.closed || loader.closing || ended)throw new IOException("Module resource retired");
            byte[] bytes=loader.entries.get(name);
            if(bytes==null)throw new IOException("Module resource unavailable");
            return bytes;
        }
        @Override public void close(){ended=true;}
    }
    private static final class ResourceHandler extends java.net.URLStreamHandler {
        private final ResourceAccess access;
        private final String name;
        ResourceHandler(ResourceAccess access,String name){this.access=access;this.name=name;}
        @Override protected java.net.URLConnection openConnection(java.net.URL url) {
            return new java.net.URLConnection(url) {
                @Override public void connect() throws IOException {access.current();connected=true;}
                @Override public InputStream getInputStream() throws IOException {connect();return new ResourceStream(access,name);}
            };
        }
    }
    /** Hash the immutable entries under a lifetime borrow, without holding the class/resource monitor. */
    public void verifyIntegrity() throws GeneralSecurityException {
        synchronized (this) {
            if (verifyingThread == Thread.currentThread())
                throw new GeneralSecurityException("Module integrity verification is already active");
            while (!closed && !closing && verifyingThread != null) {
                try { wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new GeneralSecurityException("Interrupted while awaiting module integrity access", interrupted);
                }
            }
            if (closed || closing) throw new GeneralSecurityException("Module loader retired");
            verifyingThread = Thread.currentThread();
        }
        try {
            if (!entryDigest(entries).equals(expectedEntriesDigest))
                throw new GeneralSecurityException("Retained module integrity mismatch");
        } finally {
            synchronized (this) { verifyingThread = null; notifyAll(); }
        }
    }

    /** Copies only verified game assets. The receiving scoped registry owns and wipes these buffers. */
    public synchronized Map<String, byte[]> assetSnapshot() {
        if (closed || closing) throw new IllegalStateException("Module loader retired");
        Map<String, byte[]> assets = new HashMap<>();
        boolean accepted = false;
        try {
            entries.forEach((name, bytes) -> {
                if (name.startsWith("assets/") && !name.endsWith("/") && !name.endsWith(".class"))
                    assets.put(name, bytes.clone());
            });
            Map<String, byte[]> snapshot = Map.copyOf(assets);
            accepted = true;
            return snapshot;
        } finally {
            if (!accepted) assets.values().forEach(bytes -> Arrays.fill(bytes, (byte)0));
        }
    }
    /** Dispatch only needs the revocation flag; it must not wait behind a full integrity scan. */
    public boolean isClosed() { return closed || closing; }
    @Override public synchronized void close() {
        if (verifyingThread == Thread.currentThread())
            throw new IllegalStateException("Cannot close during module integrity verification");
        if (closed) return;
        closing=true;notifyAll();
        boolean interrupted=false;
        try {
            while (verifyingThread != null) {
                try { wait(); }
                catch (InterruptedException requested) { interrupted=true; }
            }
            if (closed) return;
            closed=true;resourceAccess.owner=null;entries.values().forEach(bytes->Arrays.fill(bytes,(byte)0));entries.clear();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
