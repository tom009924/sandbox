package com.example.todo.domain.crypto

interface CryptoService {
    fun encrypt(keyName: String, plaintext: ByteArray): ByteArray
    fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray
}
