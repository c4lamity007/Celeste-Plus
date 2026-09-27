package org.slf4j; public interface Logger {default void error(String message,Object owner,Throwable error){throw new AssertionError(message,error);} }
