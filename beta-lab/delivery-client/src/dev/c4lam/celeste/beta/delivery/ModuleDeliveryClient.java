package dev.c4lam.celeste.beta.delivery;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import dev.c4lam.celeste.auth.DpopProofSigner;

import static dev.c4lam.celeste.beta.delivery.ModuleContainer.*;

/** Live-session module authorization. Persistent storage contains ciphertext only. */
public final class ModuleDeliveryClient implements AutoCloseable {
    private static final int MAX_METADATA = 512 * 1024;
    private static final int MAX_BUSY_RETRIES = 3;
    private static final long MAX_BUSY_WAIT_MILLIS = 10_000;
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final Set<String> MODULE_FIELDS = Set.of("id","version","artifact_id","entrypoint","filename","sha256","size","entries_sha256");
    private static final List<String> SESSION_BINDINGS = List.of("sub","device","cnf","product","release","client_version",
        "client_sha256","beta_catalog_sha256","hardware_sha256","process_id","session_id");
    private static final Set<String> MANIFEST_FIELDS = Set.of("schema","release_id","host_abi","module_id","version","artifact_id",
        "executable_sha256","executable_size","entries_sha256","watermark","real_record","transform_version","decoy_bytes",
        "container_sha256","container_size","ciphertext_sha256","ciphertext_size");
    private final URI origin;
    private final PublicKey serverKey;
    private final DpopProofSigner signer;
    private final SessionSource sessions;
    private final RequestEvidenceSource evidenceSource;
    private final ModuleCiphertextCache cache;
    private final Transport transport;
    private final RetryWait retryWait;
    private final String runtimeDigest, betaCatalogDigest, executableRoot;
    private final JsonObject pinnedExecutables;
    private final Map<String, Module> modules;
    private final ConcurrentHashMap<String, Capability> capabilities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();
    private volatile Catalog catalog;
    private volatile VerifiedSession verified;
    private volatile boolean closed;

    @FunctionalInterface public interface SessionSource { Session current() throws GeneralSecurityException; }
    @FunctionalInterface public interface RequestEvidenceSource {
        RequestEvidence collect() throws GeneralSecurityException, IOException;
    }
    @FunctionalInterface public interface RequestTokenSigner {
        byte[] sign(byte[] message) throws GeneralSecurityException;
    }
    /** Request-local component hashes and a signing capability; no raw hardware IDs or key export. */
    public static final class RequestEvidence implements AutoCloseable {
        private final byte[] document;
        private final RequestTokenSigner tokenSigner;
        private RequestEvidence(byte[] document, RequestTokenSigner tokenSigner) {
            this.document=document; this.tokenSigner=Objects.requireNonNull(tokenSigner);
        }
        @Override public void close() { Arrays.fill(document,(byte)0); }
    }
    public static RequestEvidence evidence(String system, String board, String processor, List<String> monitors,
                                           String hwid, String observed, RequestTokenSigner tokenSigner)
            throws GeneralSecurityException {
        for (String digest:List.of(system,board,processor,hwid,observed))
            require(digest.matches("[0-9a-f]{64}"),"Invalid module evidence digest");
        TreeSet<String> unique=new TreeSet<>(monitors);
        require(!unique.isEmpty() && unique.size()<=8
            && unique.stream().allMatch(value -> value.matches("[0-9a-f]{64}")),"Invalid monitor evidence");
        JsonObject hardware=new JsonObject();hardware.addProperty("system",system);
        hardware.addProperty("board",board);hardware.addProperty("processor",processor);
        JsonArray displays=new JsonArray();unique.forEach(displays::add);hardware.add("monitors",displays);
        JsonObject value=new JsonObject();value.addProperty("schema","celeste-module-evidence-1");
        value.add("hardware",hardware);value.addProperty("hwid_sha256",hwid);value.addProperty("observed_sha256",observed);
        return new RequestEvidence(canonical(value),tokenSigner);
    }
    /** Deliberately not a record: never print a bearer/proof-bound token through generated toString. */
    public static final class Session {
        private final String token;
        private final long deadlineNanos;
        private final String identity;
        private Session(String token,long deadlineNanos,String identity) {
            this.token=Objects.requireNonNull(token);this.deadlineNanos=deadlineNanos;this.identity=identity;
        }
    }
    /** Called after each server heartbeat, off the render thread. */
    public static Session verifiedSession(String token,long deadlineNanos,byte[] publicKey) throws GeneralSecurityException {
        JsonObject claims=BetaDeliveryClient.signed(token,"celeste-session",BetaDeliveryClient.publicKey(publicKey));
        JsonObject identity=new JsonObject();
        for (String key:SESSION_BINDINGS) {
            require(claims.has(key),"Missing runtime identity");
            identity.add(key,claims.get(key));
        }
        if (claims.has("hwid_sha256")) identity.add("hwid_sha256",claims.get("hwid_sha256"));
        long now=Instant.now().getEpochSecond(),expires=number(claims,"exp"),issued=number(claims,"iat");
        require(expires>now && issued<=now+5 && number(claims,"nbf")<=now+5 && expires-issued<=90,
            "Invalid runtime module lease");
        long remaining=Math.min(90_000_000_000L,Duration.between(Instant.now(),Instant.ofEpochSecond(expires)).toNanos());
        require(remaining>0 && deadlineNanos-System.nanoTime()>0,"Expired runtime module lease");
        long bound=System.nanoTime()+remaining;
        if(deadlineNanos-System.nanoTime()<remaining)bound=deadlineNanos;
        return new Session(token,bound,hash(canonical(identity)));
    }
    public record Module(String id,String version,String artifactId,String entrypoint,String sha256,int size,String entriesSha256) { }
    public static final class Prepared implements AutoCloseable {
        private final ModuleDeliveryClient owner;
        private final Module module;
        private final String identity;
        private volatile byte[] jar;
        private volatile boolean closed;
        private Prepared(ModuleDeliveryClient owner,Module module,byte[] jar,String identity) {
            this.owner=owner;this.module=module;this.jar=jar;this.identity=identity;
            owner.active.computeIfAbsent(module.id(),unused->new AtomicInteger()).incrementAndGet();
        }
        public Module module() { return module; }
        public boolean authorized() { return !closed && owner.authorized(module.id(),identity); }
        /** Borrowed immutable input, valid only until close; the loader must finish verification synchronously. */
        public synchronized byte[] jar() {
            if (!authorized() || jar==null) throw new SecurityException("Module bytes retired or authorization expired");
            return jar;
        }
        public synchronized void releaseBytes() { if(jar!=null){Arrays.fill(jar,(byte)0);jar=null;} }
        @Override public synchronized void close() {
            if (!closed) {
                closed=true;releaseBytes();
                owner.active.computeIfPresent(module.id(),(key,count)->count.decrementAndGet()==0?null:count);
            }
        }
        @Override public String toString() { return "PreparedModule[" + module.id() + "]"; }
    }
    private record Catalog(String digest,JsonObject data) { }
    private record VerifiedSession(Session source,JsonObject claims) { }
    @FunctionalInterface interface Transport extends AutoCloseable {
        BetaDeliveryClient.Reply request(String method,URI uri,Map<String,String> headers,byte[] body,int limit,Duration timeout)
            throws IOException,InterruptedException;
        @Override default void close() { }
    }
    @FunctionalInterface interface RetryWait { void pause(long millis) throws InterruptedException; }

    public ModuleDeliveryClient(URI origin,byte[] publicKey,DpopProofSigner signer,SessionSource sessions,
                                String runtimeDigest,String betaCatalogDigest,byte[] pinnedExecutableCatalog,Path cacheDirectory,RequestEvidenceSource evidenceSource)
            throws GeneralSecurityException,IOException {
        this(origin,publicKey,signer,sessions,runtimeDigest,betaCatalogDigest,pinnedExecutableCatalog,
            new ModuleCiphertextCache(cacheDirectory),new HttpsTransport(origin,publicKey),evidenceSource);
    }
    ModuleDeliveryClient(URI origin,byte[] publicKey,DpopProofSigner signer,SessionSource sessions,
                         String runtimeDigest,String betaCatalogDigest,byte[] pin,ModuleCiphertextCache cache,Transport transport,RequestEvidenceSource evidenceSource)
            throws GeneralSecurityException {
        this(origin,publicKey,signer,sessions,runtimeDigest,betaCatalogDigest,pin,cache,transport,evidenceSource,Thread::sleep);
    }
    ModuleDeliveryClient(URI origin,byte[] publicKey,DpopProofSigner signer,SessionSource sessions,
                         String runtimeDigest,String betaCatalogDigest,byte[] pin,ModuleCiphertextCache cache,Transport transport,
                         RequestEvidenceSource evidenceSource,RetryWait retryWait) throws GeneralSecurityException {
        require(origin!=null && "https".equalsIgnoreCase(origin.getScheme()) && origin.getHost()!=null
            && origin.getRawUserInfo()==null && origin.getRawQuery()==null && origin.getRawFragment()==null
            && (origin.getRawPath().isEmpty() || origin.getRawPath().equals("/")),"Invalid module service origin");
        require(runtimeDigest.matches("[0-9a-f]{64}") && betaCatalogDigest.matches("[0-9a-f]{64}")
            && pin.length>0 && pin.length<=MAX_METADATA,"Invalid module trust context");
        this.origin=origin.resolve("/");this.serverKey=BetaDeliveryClient.publicKey(publicKey);
        this.signer=Objects.requireNonNull(signer);this.sessions=Objects.requireNonNull(sessions);
        this.evidenceSource=Objects.requireNonNull(evidenceSource);
        this.cache=Objects.requireNonNull(cache);this.transport=Objects.requireNonNull(transport);
        this.retryWait=Objects.requireNonNull(retryWait);
        this.runtimeDigest=runtimeDigest;this.betaCatalogDigest=betaCatalogDigest;
        pinnedExecutables=BetaDeliveryClient.object(pin,512);
        exact(pinnedExecutables,Set.of("schema","product","release_id","host_abi","modules"));
        require(text(pinnedExecutables,"schema").equals("celeste-module-executables-1")
            && text(pinnedExecutables,"product").equals("celeste_plus") && number(pinnedExecutables,"host_abi")==1,
            "Unsupported module executable catalog");
        executableRoot=hash(canonical(pinnedExecutables));
        modules=parseModules(pinnedExecutables);
    }
    public List<Module> modules() { return List.copyOf(modules.values()); }

    private VerifiedSession session() throws GeneralSecurityException {
        require(!closed,"Module delivery retired");
        Session snapshot=sessions.current();
        require(snapshot!=null && snapshot.deadlineNanos-System.nanoTime()>0,"Runtime lease expired");
        // Session tokens are immutable and replaced by the resident authority on renewal.
        // Recheck liveness on every use, but verify/parse a given token only once per client.
        VerifiedSession cached=verified;
        if(cached!=null && cached.source==snapshot) return cached;
        JsonObject claims=BetaDeliveryClient.signed(snapshot.token,"celeste-session",serverKey);
        signer.requireBinding(claims);
        long now=Instant.now().getEpochSecond();
        require(text(claims,"product").equals("celeste_plus") && text(claims,"status").equals("active")
            && text(claims,"client_sha256").equals(runtimeDigest) && text(claims,"beta_catalog_sha256").equals(betaCatalogDigest)
            && text(claims,"release").equals(text(pinnedExecutables,"release_id"))
            && number(claims,"iat")<=now+5 && number(claims,"nbf")<=now+5
            && number(claims,"exp")>now && number(claims,"exp")-number(claims,"iat")<=90,
            "Invalid module runtime lease");
        VerifiedSession result=new VerifiedSession(snapshot,claims);
        synchronized(capabilities){require(!closed,"Module delivery retired");verified=result;}
        return result;
    }
    private void stillCurrent(VerifiedSession request) throws GeneralSecurityException {
        VerifiedSession current=session();
        for (String key:SESSION_BINDINGS) require(Objects.equals(current.claims.get(key),request.claims.get(key)),"Runtime identity changed");
        require(Objects.equals(current.claims.get("hwid_sha256"),request.claims.get("hwid_sha256")),"Runtime hardware changed");
        require(request.source.deadlineNanos-System.nanoTime()>0,"Request outlived its lease");
    }
    private static Map<String,Module> parseModules(JsonObject root) throws GeneralSecurityException {
        JsonElement raw=root.get("modules");
        require(raw!=null && raw.isJsonArray() && raw.getAsJsonArray().size()>0 && raw.getAsJsonArray().size()<=512,"Invalid module roster");
        Map<String,Module> result=new LinkedHashMap<>();
        Set<String> artifacts=new HashSet<>();
        long total=0;
        for (JsonElement element:raw.getAsJsonArray()) {
            require(element.isJsonObject(),"Invalid module entry");
            JsonObject item=element.getAsJsonObject();exact(item,MODULE_FIELDS);
            String id=text(item,"id"),version=text(item,"version"),artifact=text(item,"artifact_id"),entry=text(item,"entrypoint");
            long size=number(item,"size");
            require(id.matches("[A-Za-z0-9][A-Za-z0-9_. -]{0,79}") && version.matches("[A-Za-z0-9][A-Za-z0-9_. -]{0,79}")
                && artifact.matches("[0-9a-f]{32}") && artifacts.add(artifact) && text(item,"filename").equals(artifact+".jar")
                && entry.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
                && size>0 && size<=16*1024*1024,"Invalid module identity");
            Module module=new Module(id,version,artifact,entry,hex(item,"sha256"),(int)size,hex(item,"entries_sha256"));
            require(result.putIfAbsent(id,module)==null,"Duplicate module identity");total+=size;
        }
        require(total<=256L*1024*1024,"Module release exceeds bound");
        return Collections.unmodifiableMap(result);
    }

    /** Coalesced by caller; the returned roster is always checked against the immutable resident pin. */
    public synchronized void prepareCatalog() throws GeneralSecurityException,IOException,InterruptedException {
        prepareCatalog(NEVER_CANCELLED);
    }
    private synchronized void prepareCatalog(BooleanSupplier cancelled) throws GeneralSecurityException,IOException,InterruptedException {
        checkCancelled(cancelled);
        require(!closed,"Module delivery retired");
        if(catalog==null)fetchCatalog(cancelled);
    }

    /** Explicit signed refresh; startup prefetch and activation share prepareCatalog instead. */
    public synchronized void fetchCatalog() throws GeneralSecurityException,IOException,InterruptedException {
        fetchCatalog(NEVER_CANCELLED);
    }
    private synchronized void fetchCatalog(BooleanSupplier cancelled) throws GeneralSecurityException,IOException,InterruptedException {
        checkCancelled(cancelled);
        VerifiedSession state=session();
        try (Response response=request("GET","/v1/modules/catalog",new byte[0],null,MAX_METADATA,state,state.source.deadlineNanos,cancelled)) {
            JsonObject wrapper=BetaDeliveryClient.object(response.bytes,512);exact(wrapper,Set.of("catalog","proof"));
            JsonObject value=objectMember(wrapper,"catalog");
            exact(value,Set.of("schema","product","release_id","host_abi","runtime_sha256","executable_root_sha256","modules"));
            require(text(value,"schema").equals("celeste-module-release-1") && text(value,"runtime_sha256").equals(runtimeDigest)
                && text(value,"executable_root_sha256").equals(executableRoot),"Module catalog host mismatch");
            JsonObject subset=value.deepCopy();subset.remove("runtime_sha256");subset.remove("executable_root_sha256");
            subset.addProperty("schema","celeste-module-executables-1");
            require(BetaDeliveryClient.canonical(subset).equals(BetaDeliveryClient.canonical(pinnedExecutables)),
                "Module catalog changes approved executable content");
            String digest=hash(canonical(value));
            JsonObject proof=BetaDeliveryClient.signed(text(wrapper,"proof"),"celeste-module-catalog",serverKey);
            verifyBinding(proof,state,response.nonce,digest,response.evidenceDigest);stillCurrent(state);checkCancelled(cancelled);
            catalog=new Catalog(digest,value);
        }
    }
    private void verifyBinding(JsonObject claims,VerifiedSession state,String nonce,String digest,String evidenceDigest) throws GeneralSecurityException {
        signer.requireBinding(claims);
        for (String key:SESSION_BINDINGS) require(Objects.equals(claims.get(key),state.claims.get(key)),"Module session binding mismatch");
        require(Objects.equals(claims.get("hwid_sha256"),state.claims.get("hwid_sha256")),"Module hardware binding mismatch");
        long now=Instant.now().getEpochSecond(),expires=number(claims,"exp"),issued=number(claims,"iat");
        require(number(claims,"host_abi")==1 && text(claims,"catalog_sha256").equals(digest)
            && text(claims,"executable_root_sha256").equals(executableRoot)
            && text(claims,"session_token_sha256").equals(hash(state.source.token.getBytes(StandardCharsets.US_ASCII)))
            && text(claims,"request_nonce").equals(nonce) && text(claims,"evidence_sha256").equals(evidenceDigest) && issued<=now+5 && number(claims,"nbf")<=now+5
            && expires>now && expires<=number(state.claims,"exp") && expires-issued<=90 && expires>issued,
            "Module capability is stale or belongs to another request");
    }
    /** Batches preserve arbitrary legitimate active-module counts and fit the existing 16KiB request limit. */
    public synchronized void authorize(Collection<String> ids) throws GeneralSecurityException,IOException,InterruptedException {
        authorize(ids,NEVER_CANCELLED);
    }
    /** Cancellation stops only this operation; it never revokes other modules' live permissions. */
    public synchronized void authorize(Collection<String> ids,BooleanSupplier cancelled) throws GeneralSecurityException,IOException,InterruptedException {
        prepareCatalog(cancelled);
        List<String> requested=List.copyOf(new LinkedHashSet<>(ids));
        for (String id:requested) require(modules.containsKey(id),"Unknown requested module");
        for (int begin=0;begin<requested.size();begin+=32) {
            checkCancelled(cancelled);
            List<String> group=requested.subList(begin,Math.min(requested.size(),begin+32));
            VerifiedSession state=session();Catalog pinned=catalog;
            JsonObject body=new JsonObject();body.addProperty("catalog_sha256",pinned.digest);
            JsonArray array=new JsonArray();group.forEach(array::add);body.add("modules",array);
            byte[] input=canonical(body);
            Map<String,Capability> pending=new LinkedHashMap<>();
            boolean accepted=false;
            try (Response response=request("POST","/v1/modules/authorize",input,null,MAX_METADATA,state,state.source.deadlineNanos,cancelled)) {
                JsonObject wrapper=BetaDeliveryClient.object(response.bytes,512);
                exact(wrapper,Set.of("catalog_sha256","expires_at","capabilities"));
                require(text(wrapper,"catalog_sha256").equals(pinned.digest),"Module authorization catalog mismatch");
                JsonElement raw=wrapper.get("capabilities");
                require(raw!=null && raw.isJsonArray() && raw.getAsJsonArray().size()==group.size(),"Incomplete module authorization");
                for (JsonElement element:raw.getAsJsonArray()) {
                    require(element.isJsonObject(),"Invalid module capability");
                    JsonObject item=element.getAsJsonObject();exact(item,Set.of("module_id","capability","key","manifest","url"));
                    String id=text(item,"module_id"),token=text(item,"capability");
                    require(group.contains(id) && !pending.containsKey(id),"Unexpected module capability");
                    JsonObject claims=BetaDeliveryClient.signed(token,"celeste-module-capability",serverKey);
                    verifyBinding(claims,state,response.nonce,pinned.digest,response.evidenceDigest);
                    require(text(claims,"module_id").equals(id) && number(wrapper,"expires_at")<=number(claims,"exp"),"Module capability mismatch");
                    JsonObject manifest=objectMember(item,"manifest");
                    require(manifest.equals(objectMember(claims,"manifest")),"Unsigned module personalization");
                    Module module=modules.get(id);verifyManifest(manifest,module);
                    String url="/v1/modules/artifacts/"+text(pinnedExecutables,"release_id")+"/"+module.artifactId();
                    require(text(item,"url").equals(url),"Unexpected module artifact URL");
                    byte[] key=BetaDeliveryClient.decode(text(item,"key"));
                    try {
                        require(key.length==32,"Invalid module content key");
                        long remaining=Math.min(90_000_000_000L,Math.max(0,Duration.between(Instant.now(),
                            Instant.ofEpochSecond(number(claims,"exp"))).toNanos()));
                        long deadline=System.nanoTime()+remaining;
                        if (state.source.deadlineNanos-System.nanoTime()<remaining) deadline=state.source.deadlineNanos;
                        pending.put(id,new Capability(token,key,manifest,url,deadline,state.source.identity));key=null;
                    } finally { if (key!=null) Arrays.fill(key,(byte)0); }
                }
                stillCurrent(state);
                synchronized(capabilities) {
                    checkCancelled(cancelled);
                    require(!closed,"Module delivery retired");
                    pending.forEach((id,cap)->{Capability old=capabilities.put(id,cap);if(old!=null)old.close();});
                    accepted=true;
                }
            } finally {
                Arrays.fill(input,(byte)0);
                if (!accepted) pending.values().forEach(Capability::close);
            }
        }
    }
    private void verifyManifest(JsonObject value,Module module) throws GeneralSecurityException {
        exact(value,MANIFEST_FIELDS);
        require(text(value,"schema").equals("celeste-module-personalization-1")
            && text(value,"release_id").equals(text(pinnedExecutables,"release_id")) && number(value,"host_abi")==1
            && text(value,"module_id").equals(module.id()) && text(value,"version").equals(module.version())
            && text(value,"artifact_id").equals(module.artifactId()) && text(value,"executable_sha256").equals(module.sha256())
            && number(value,"executable_size")==module.size() && text(value,"entries_sha256").equals(module.entriesSha256())
            && text(value,"watermark").matches("[0-9a-f]{32}") && text(value,"real_record").matches("[0-9a-f]{32}")
            && number(value,"transform_version")==1 && number(value,"decoy_bytes")<=module.size()/10L
            && number(value,"container_size")>module.size() && number(value,"container_size")<=ModuleContainer.MAX_BYTES-28
            && number(value,"ciphertext_size")==number(value,"container_size")+28,"Invalid module personalization");
        hex(value,"container_sha256");hex(value,"ciphertext_sha256");
    }
    /** Standalone activation always reauthorizes, even with valid cached keys/ciphertext. */
    public Prepared load(String id) throws GeneralSecurityException,IOException,InterruptedException {
        authorize(List.of(id));
        return loadAuthorized(id);
    }
    /** The host activation queue has freshly authorized this module as part of its current batch. */
    public Prepared loadAuthorized(String id) throws GeneralSecurityException,IOException,InterruptedException {
        return loadAuthorized(id,NEVER_CANCELLED);
    }
    public Prepared loadAuthorized(String id,BooleanSupplier cancelled) throws GeneralSecurityException,IOException,InterruptedException {
        checkCancelled(cancelled);
        require(modules.containsKey(id),"Unknown module");
        require(authorized(id),"A fresh activation batch must authorize the module first");
        // The activation batch just authorized this module. Repeating that request when
        // less than 30 seconds remain cannot extend the enclosing runtime lease.
        // Authorization renewal replaces and wipes the map's previous key. A bounded
        // operation owns a separate copy so that concurrent renewal cannot corrupt it.
        Capability capability;
        synchronized(capabilities) {
            Capability current=capabilities.get(id);
            require(current!=null && authorized(id),"Module capability unavailable");
            capability=current.copy();
        }
        try (capability) {
        VerifiedSession state=session();
        require(state.source.identity.equals(capability.identity),"Module preparation belongs to another runtime");
        JsonObject manifest=capability.manifest;
        int length=Math.toIntExact(number(manifest,"ciphertext_size"));String digest=hex(manifest,"ciphertext_sha256");
        byte[] bytes;
        try { bytes=cache.read(digest,length); }
        catch (IOException unavailableCache) { bytes=null; }
        if (bytes==null) {
            try (Response response=request("GET",capability.url,new byte[0],capability.token,length,state,capability.deadline,cancelled)) {
                require(response.bytes.length==length && hash(response.bytes).equals(digest),"Module ciphertext download mismatch");
                stillCurrent(state);require(authorized(id),"Module capability expired during download");
                checkCancelled(cancelled);
                // Cache availability is optional; the authenticated in-memory response
                // still receives the same complete container verification below.
                try { cache.put(digest,response.bytes); }
                catch (IOException unavailableCache) { /* Download remains usable for this activation. */ }
                bytes=response.takeBytes();
            }
        }
        byte[] jar=null;
        try {
            checkCancelled(cancelled);
            stillCurrent(state);require(authorized(id),"Module capability expired before decryption");
            jar=capability.decrypt(bytes,catalog.digest);
            checkCancelled(cancelled);
            stillCurrent(state);require(authorized(id),"Module capability expired during preparation");
            Prepared result=new Prepared(this,modules.get(id),jar,capability.identity);jar=null;return result;
        } finally { Arrays.fill(bytes,(byte)0);if(jar!=null)Arrays.fill(jar,(byte)0); }
        }
    }
    /** Called once from the existing heartbeat, never from rendering or tick callbacks. */
    public void renewActive() throws GeneralSecurityException,IOException,InterruptedException {
        expire();List<String> ids=List.copyOf(active.keySet());if(!ids.isEmpty())authorize(ids);
    }
    public boolean authorized(String id) {
        return authorized(id,null);
    }
    private boolean authorized(String id,String identity) {
        if (closed) return false;
        Capability capability=capabilities.get(id);
        if (capability==null || !capability.current() || identity!=null && !identity.equals(capability.identity)) return false;
        try {
            // SessionSource is the resident authority; signature verification stays off hot paths.
            Session current=sessions.current();
            return current!=null && current.deadlineNanos-System.nanoTime()>0 && current.identity.equals(capability.identity);
        } catch (GeneralSecurityException | RuntimeException failure) { return false; }
    }
    public void expire() {
        capabilities.forEach((id,cap)->{if(!cap.current() && capabilities.remove(id,cap))cap.close();});
    }
    @Override public void close() {
        synchronized(capabilities) {
            if(closed)return;
            closed=true;capabilities.values().forEach(Capability::close);capabilities.clear();active.clear();verified=null;
        }
        transport.close();
    }
    private static final class Capability implements AutoCloseable {
        final String token,url,identity;final JsonObject manifest;final long deadline;
        private byte[] key;
        Capability(String token,byte[] key,JsonObject manifest,String url,long deadline,String identity) {
            this.token=token;this.key=key;this.manifest=manifest.deepCopy();this.url=url;this.deadline=deadline;this.identity=identity;
        }
        synchronized boolean current() { return key!=null && deadline-System.nanoTime()>0; }
        synchronized Capability copy() throws GeneralSecurityException {
            require(current(),"Module key expired");
            return new Capability(token,key.clone(),manifest,url,deadline,identity);
        }
        synchronized byte[] decrypt(byte[] bytes,String catalog) throws GeneralSecurityException {
            require(current(),"Module key expired");return ModuleContainer.open(bytes,key,manifest,catalog);
        }
        @Override public synchronized void close() { if(key!=null){Arrays.fill(key,(byte)0);key=null;} }
    }
    private static final class Response implements AutoCloseable {
        byte[] bytes;final String nonce,evidenceDigest;
        Response(byte[] bytes,String nonce,String evidenceDigest){this.bytes=bytes;this.nonce=nonce;this.evidenceDigest=evidenceDigest;}
        byte[] takeBytes(){byte[] result=bytes;bytes=null;return result;}
        @Override public void close(){if(bytes!=null){Arrays.fill(bytes,(byte)0);bytes=null;}}
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean())throw new CancellationException("Module activation cancelled");
    }
    private Response request(String method,String path,byte[] content,String capability,int limit,VerifiedSession state,long capabilityDeadline,BooleanSupplier cancelled)
            throws GeneralSecurityException,IOException,InterruptedException {
        URI uri=origin.resolve(path);
        require(path.startsWith("/v1/modules/") && !path.contains("..") && uri.getRawQuery()==null,"Invalid module request path");
        long waitedMillis=0;
        // Only the delivery/authorization workers call this path. Every retry is
        // a newly measured and signed request; never replay a consumed proof.
        for(int attempt=0;;attempt++) {
            checkCancelled(cancelled);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Module request interrupted");
            stillCurrent(state);
            require(capabilityDeadline-System.nanoTime()>0,"Module request capability expired");
            long retryMillis;
            try (ArtifactEnvelope envelope=new ArtifactEnvelope(); RequestEvidence evidence=evidenceSource.collect()) {
                checkCancelled(cancelled);
                String hardware=hex(state.claims,"hwid_sha256");
                JsonObject evidenceValue=BetaDeliveryClient.object(evidence.document);
                require(hex(evidenceValue,"hwid_sha256").equals(hardware)
                    && hex(evidenceValue,"observed_sha256").equals(runtimeDigest),"Current module device or client integrity changed");
                JsonObject core=objectMember(evidenceValue,"hardware").deepCopy();core.remove("monitors");
                require(hash(canonical(core)).equals(hex(state.claims,"hardware_sha256")),"Current SMBIOS identity changed");
                Map<String,String> headers=new LinkedHashMap<>();
                headers.put("Authorization","DPoP "+state.source.token);
                String proof=signer.proofForModule(method,uri,state.source.token,envelope.publicKey(),envelope.nonce(),hardware,content,evidence.document);
                headers.put("DPoP",proof);
                headers.put("X-Celeste-Module-Evidence",Base64.getUrlEncoder().withoutPadding().encodeToString(evidence.document));
                byte[] proofMessage=("celeste-module-proof-v1\n"+proof).getBytes(StandardCharsets.UTF_8);
                byte[] tokenProof=null;
                try {
                    tokenProof=evidence.tokenSigner.sign(proofMessage);
                    require(tokenProof!=null && tokenProof.length==64,"Invalid module token proof");
                    headers.put("X-Celeste-Module-Token-Proof",Base64.getUrlEncoder().withoutPadding().encodeToString(tokenProof));
                } finally { Arrays.fill(proofMessage,(byte)0);if(tokenProof!=null)Arrays.fill(tokenProof,(byte)0); }
                headers.put("X-Device-Id",hardware);headers.put("X-Celeste-Delivery-Key",envelope.publicKey());
                headers.put("X-Celeste-Request-Nonce",envelope.nonce());headers.put("Accept","application/octet-stream");
                headers.put("Cache-Control","no-store");if(method.equals("POST"))headers.put("Content-Type","application/json");
                if(capability!=null)headers.put("X-Celeste-Module-Capability",capability);
                stillCurrent(state);
                checkCancelled(cancelled);
                long remaining=Math.min(state.source.deadlineNanos-System.nanoTime(),capabilityDeadline-System.nanoTime());
                require(remaining>0,"Request outlived its lease");
                BetaDeliveryClient.Reply reply=transport.request(method,uri,Map.copyOf(headers),content,limit+ArtifactEnvelope.OVERHEAD,
                    Duration.ofNanos(Math.min(Duration.ofSeconds(20).toNanos(),remaining)));
                byte[] bytes=reply.body();
                try {
                    if(!uri.equals(reply.uri()))throw new IOException("Module response origin rejected");
                    if (reply.status()==401 || reply.status()==403 || reply.status()==409) {
                        close();
                        throw new GeneralSecurityException("Module authorization rejected ("+reply.status()+")");
                    }
                    if(reply.status()==503) {
                        List<String> after=BetaDeliveryClient.values(reply.headers(),"Retry-After");
                        if(attempt>=MAX_BUSY_RETRIES || after.size()!=1 || !after.getFirst().matches("[1-3]"))
                            throw new IOException("Module service is busy; retry activation shortly");
                        retryMillis=Long.parseLong(after.getFirst())*1000+ThreadLocalRandom.current().nextLong(251);
                    } else {
                        if (reply.status()!=200 || bytes==null
                                || bytes.length<=ArtifactEnvelope.OVERHEAD || bytes.length>limit+ArtifactEnvelope.OVERHEAD)
                            throw new IOException("Module response origin, status or size rejected");
                        List<String> types=BetaDeliveryClient.values(reply.headers(),"Content-Type");
                        require(types.size()==1 && types.getFirst().split(";",2)[0].trim().equalsIgnoreCase("application/octet-stream"),"Unexpected module content type");
                        List<String> lengths=BetaDeliveryClient.values(reply.headers(),"Content-Length");
                        require(lengths.size()<=1 && (lengths.isEmpty() || lengths.getFirst().equals(Integer.toString(bytes.length))),"Invalid module content length");
                        List<String> encoding=BetaDeliveryClient.values(reply.headers(),"Content-Encoding");
                        require(encoding.isEmpty() || encoding.size()==1 && encoding.getFirst().equalsIgnoreCase("identity"),"Encoded module response rejected");
                        stillCurrent(state);
                        byte[] opened=envelope.open(bytes,path,state.source.token,bytes.length-ArtifactEnvelope.OVERHEAD);boolean accepted=false;
                        try {
                            checkCancelled(cancelled);
                            Response response=new Response(opened,envelope.nonce(),hash(evidence.document));accepted=true;return response;
                        } finally { if(!accepted)Arrays.fill(opened,(byte)0); }
                    }
                } finally { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
            }
            stillCurrent(state);
            checkCancelled(cancelled);
            if(Math.min(state.source.deadlineNanos-System.nanoTime(),capabilityDeadline-System.nanoTime())
                <=Duration.ofMillis(retryMillis).toNanos())throw new IOException("Module busy retry cannot finish within its current lease");
            if(waitedMillis+retryMillis>MAX_BUSY_WAIT_MILLIS)throw new IOException("Module busy wait limit exceeded");
            retryWait.pause(retryMillis);waitedMillis+=retryMillis;
        }
    }
    private static byte[] canonical(JsonObject value) throws GeneralSecurityException {
        return BetaDeliveryClient.canonical(value).getBytes(StandardCharsets.UTF_8);
    }
    private static String hex(JsonObject value,String key) throws GeneralSecurityException {
        String digest=text(value,key);require(digest.matches("[0-9a-f]{64}"),"Invalid module SHA256");return digest;
    }
    private static JsonObject objectMember(JsonObject value,String key) throws GeneralSecurityException {
        JsonElement member=value.get(key);require(member!=null && member.isJsonObject(),"Missing module metadata object");return member.getAsJsonObject();
    }
    private static void exact(JsonObject value,Set<String> fields) throws GeneralSecurityException {
        require(value.keySet().equals(fields),"Unexpected module metadata fields");
    }
}
