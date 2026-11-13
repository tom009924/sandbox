package com.example.todo.domain.crypto

open class CryptoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class CryptoKeyNotFoundException(
    message: String,
    cause: Throwable? = null,
) : CryptoException(message, cause)
