package dev.c4lam.celeste.beta.memory;

import java.security.GeneralSecurityException;

/** Only a successfully completed retained-byte hash comparison may construct this type. */
public final class RetainedIntegrityException extends GeneralSecurityException {
    private static final long serialVersionUID=1L;
    private final String expected,observed;
    public RetainedIntegrityException(String expected,String observed) {
        super("Retained module integrity mismatch");
        if(expected==null || observed==null || !expected.matches("[0-9a-f]{64}")
                || !observed.matches("[0-9a-f]{64}") || expected.equals(observed))
            throw new IllegalArgumentException("Expected a confirmed retained-byte mismatch");
        this.expected=expected;this.observed=observed;
    }
    public String expected(){return expected;}
    public String observed(){return observed;}
}
