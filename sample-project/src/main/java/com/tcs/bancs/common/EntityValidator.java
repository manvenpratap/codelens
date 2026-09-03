package com.tcs.bancs.common;

public interface EntityValidator<T> {
    boolean isValid(T entity);
}
