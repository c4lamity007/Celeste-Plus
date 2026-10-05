package dev.c4lam.celeste.auth;

import java.security.PublicKey;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import static dev.c4lam.celeste.auth.SecurityIncidentProtocol.*;

/** One authorization generation. poll/watchdog run on workers; renderer only marks visible frames. */
final class SecurityIncidentController implements AutoCloseable {
    enum State { CLEAR, WARNING, CONFIRMING, OUTCOME, REVOKED, EXPIRED, CLOSED }
    interface Clock { long nanos(); long epochMillis(); }
    interface Call { CompletionStage<String> response(); void cancel(); }
    interface Transport {
        /** Must return promptly; network I/O is asynchronous and bounded by deadlineNanos. */
        Call report(Request request,long deadlineNanos);
    }
    interface Effects {
        /** Enqueue on the host thread that also owns changing currentGeneration. Never run cleanup here. */
        void enqueue(Runnable action);
        /** Called on that host thread; must return promptly. The synchronous dispatch fence is separate. */
        void quarantine();
        void scheduleStop();
        /** Release only this controller's owned reporter/key references, not another generation. */
        void releaseReporter();
        void failed(Throwable failure);
    }
    private static final long SECOND=1_000_000_000L, REQUEST_LIMIT=5*SECOND, OUTCOME_LIMIT=10*SECOND;
    private final Binding binding;
    private final PublicKey serverKey;
    private final Clock clock;
    private final Supplier<String> randomId;
    private final Transport transport;
    private final Effects effects;
    private final long generation,leaseDeadline;
    private final LongSupplier currentGeneration;
    private State state=State.CLEAR;
    private Sample first,confirmation;
    private Observation lastOtherFamily;
    private String incident;
    private Decision warning;
    private Pending inFlight;
    private long visibleNanos,lastFrame,nextAttempt,outcomeDeadline;
    private boolean frameSeen,stopQueued,reporterReleased;
    private static final class Pending {
        final Request request; final long deadline;
        Call call;
        Pending(Request request,long deadline){this.request=request;this.deadline=deadline;}
    }

    SecurityIncidentController(Binding binding,PublicKey serverKey,Clock clock,Supplier<String> randomId,
                               Transport transport,Effects effects,long generation,LongSupplier currentGeneration,
                               long leaseDeadline) {
        this.binding=Objects.requireNonNull(binding);this.serverKey=Objects.requireNonNull(serverKey);
        this.clock=Objects.requireNonNull(clock);this.randomId=Objects.requireNonNull(randomId);
        this.transport=Objects.requireNonNull(transport);this.effects=Objects.requireNonNull(effects);
        this.generation=generation;this.currentGeneration=Objects.requireNonNull(currentGeneration);
        long remaining=leaseDeadline-clock.nanos();
        if(remaining<=0||remaining>90*SECOND)throw new IllegalArgumentException("Invalid original host lease");
        this.leaseDeadline=leaseDeadline;nextAttempt=clock.nanos();
    }

    /** Null/cleared/unavailable samples do not count. Every nonnull input must be freshly measured now. */
    void poll(Observation observation) { advance(observation); }
    /** Independent supervisor call: expires a hung HTTP stage even if its worker never returns. */
    void watchdog() { advance(null); }

    private void advance(Observation observation) {
        Call cancel=null; boolean quarantine=false,release=false;
        Pending send=null;
        synchronized(this) {
            long now=clock.nanos();
            if(state==State.CLOSED||state==State.EXPIRED||state==State.REVOKED)return;
            if(currentGeneration.getAsLong()!=generation) {
                state=State.CLOSED;cancel=detach();release=true;
            } else {
                long end=(state==State.CONFIRMING||state==State.OUTCOME)?outcomeDeadline:leaseDeadline;
                if(warning!=null&&state==State.WARNING)end=earlier(end,warning.expiresNanos(),now);
                if(now-end>=0) {
                    state=State.EXPIRED;cancel=detach();release=true;
                } else {
                    if(inFlight!=null&&now-inFlight.deadline>=0) {
                        Phase phase=inFlight.request.phase();cancel=detach();
                        if(phase==Phase.CONFIRM)state=State.OUTCOME;
                        nextAttempt=now;
                    }
                    if(state==State.CLEAR&&observation!=null) {
                        first=sample(observation);incident=randomId.get();state=State.WARNING;quarantine=true;
                    }
                    if(state==State.WARNING&&observation!=null&&!first.observation().sameFamily(observation)
                            &&(lastOtherFamily==null||!lastOtherFamily.sameFamily(observation))) {
                        // A different family only starts a fresh local warning; it cannot confirm this incident.
                        lastOtherFamily=observation;visibleNanos=0;frameSeen=false;
                    }
                    if(inFlight==null&&now-nextAttempt>=0) {
                        Phase phase=null;Sample evidence=null;
                        if(state==State.WARNING&&warning==null){phase=Phase.BEGIN;evidence=first;}
                        else if(state==State.WARNING&&warning!=null&&visibleNanos>=WARNING_NANOS
                                &&now-warning.confirmAfterNanos()>=0&&first.observation().sameFamily(observation)) {
                            phase=Phase.CONFIRM;confirmation=sample(observation);evidence=confirmation;
                            state=State.CONFIRMING;
                            // Only a small retrieval grace, never a renewed host/gameplay lease.
                            outcomeDeadline=earlier(now+OUTCOME_LIMIT,leaseDeadline+OUTCOME_LIMIT,now);
                        } else if(state==State.OUTCOME){phase=Phase.OUTCOME;evidence=confirmation;}
                        if(phase!=null) {
                            long limit=phase==Phase.OUTCOME?outcomeDeadline:leaseDeadline;
                            if(warning!=null&&phase==Phase.CONFIRM)limit=earlier(limit,warning.expiresNanos(),now);
                            Request request=new Request(binding,incident,randomId.get(),phase,first.id(),evidence,
                                phase==Phase.BEGIN?"":warning.challenge(),clock.epochMillis());
                            send=new Pending(request,earlier(now+REQUEST_LIMIT,limit,now));inFlight=send;
                        }
                    }
                }
            }
        }
        cancel(cancel);if(release)releaseReporter();
        if(quarantine)queueAction(false);
        if(send!=null)send(send);
    }

    private Sample sample(Observation observation){return new Sample(observation,randomId.get(),clock.epochMillis());}
    private static long earlier(long a,long b,long now){return a-now<=b-now?a:b;}
    private Call detach(){Pending old=inFlight;inFlight=null;return old==null?null:old.call;}

    private void send(Pending pending) {
        synchronized(this){if(inFlight!=pending||terminal()||currentGeneration.getAsLong()!=generation)return;}
        Call call;
        try {call=Objects.requireNonNull(transport.report(pending.request,pending.deadline));}
        catch(RuntimeException failure){complete(pending,null,failure);return;}
        boolean accepted;
        synchronized(this){accepted=inFlight==pending&&!terminal();if(accepted)pending.call=call;}
        if(!accepted){cancel(call);return;}
        try{call.response().whenComplete((wire,failure)->complete(pending,wire,failure));}
        catch(RuntimeException failure){complete(pending,null,failure);}
    }

    private void complete(Pending pending,String wire,Throwable failure) {
        Decision decision=null;
        if(failure==null)try {
            decision=SecurityIncidentProtocol.verify(serverKey,pending.request,wire,clock.epochMillis()/1000,clock.nanos());
        } catch(java.security.GeneralSecurityException rejected){failure=rejected;}
        boolean stop=false,release=false;
        synchronized(this) {
            if(inFlight!=pending||terminal())return;
            long now=clock.nanos();inFlight=null;
            if(currentGeneration.getAsLong()!=generation) {state=State.CLOSED;release=true;}
            else if(now-pending.deadline>=0) {
                // An already-timed-out mutation can only be recovered through fresh outcome proof.
                if(pending.request.phase()==Phase.CONFIRM)state=State.OUTCOME;
                nextAttempt=now;
            } else if(failure!=null) {
                if(pending.request.phase()==Phase.CONFIRM)state=State.OUTCOME;
                nextAttempt=now+SECOND;
            } else if(decision.revoked()) {
                if(warning!=null&&visibleNanos>=WARNING_NANOS&&confirmation!=null) {
                    state=State.REVOKED;if(!stopQueued){stopQueued=true;stop=true;}release=true;
                }
            } else if(decision.status()==Status.PENDING) {
                // The server atomically tombstones this attempt: a delayed CONFIRM cannot commit later.
                confirmation=null;state=State.WARNING;nextAttempt=now+SECOND;
            } else warning=decision;
        }
        if(stop)queueAction(true);if(release)releaseReporter();
    }

    private boolean terminal(){return state==State.CLOSED||state==State.EXPIRED||state==State.REVOKED;}
    private void queueAction(boolean stop) {
        try {effects.enqueue(()->{
            // Host replacement and these effects are serialized on the SAME host thread.
            // This second fence also catches close/replacement while the action sat in the queue.
            synchronized(this){if(state==State.CLOSED||state==State.EXPIRED||currentGeneration.getAsLong()!=generation
                    ||stop&&state!=State.REVOKED)return;}
            try{if(stop)effects.scheduleStop();else effects.quarantine();}
            catch(RuntimeException failure){failure(failure);}
        });}catch(RuntimeException failure){failure(failure);}
    }
    private void releaseReporter() {
        synchronized(this){if(reporterReleased)return;reporterReleased=true;}
        try{effects.releaseReporter();}catch(RuntimeException failure){failure(failure);}
    }
    private void cancel(Call call){if(call!=null)try{call.cancel();}catch(RuntimeException failure){failure(failure);}}
    private void failure(Throwable failure){try{effects.failed(failure);}catch(RuntimeException ignored){/* no punitive action */}}

    /** Call only after red Stop. was drawn in a visible window. Large/minimized frame gaps do not count. */
    synchronized void renderedWarningFrame() {
        if(state!=State.WARNING&&state!=State.CONFIRMING&&state!=State.OUTCOME)return;
        if(visibleNanos>=WARNING_NANOS)return;
        long now=clock.nanos();
        if(frameSeen){long elapsed=now-lastFrame;if(elapsed>=0&&elapsed<=250_000_000L)
            visibleNanos=Math.min(WARNING_NANOS,visibleNanos+elapsed);}
        frameSeen=true;lastFrame=now;
    }
    synchronized boolean showWarning(){return (state==State.WARNING||state==State.CONFIRMING||state==State.OUTCOME)&&visibleNanos<WARNING_NANOS;}
    synchronized State state(){return state;}
    synchronized boolean accessBlocked(){return state!=State.CLEAR||currentGeneration.getAsLong()!=generation||clock.nanos()-leaseDeadline>=0;}
    @Override public void close(){Call cancel;synchronized(this){state=State.CLOSED;cancel=detach();warning=null;first=null;confirmation=null;incident=null;}cancel(cancel);releaseReporter();}
}
