package com.example.todo.domain.exception

class IdempotencyConflictException(key: String) :
    RuntimeException("Idempotency key '$key' was already used with a different request")
