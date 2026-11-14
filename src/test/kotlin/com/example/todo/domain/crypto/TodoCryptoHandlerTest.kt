package com.example.todo.domain.crypto

import com.example.todo.domain.model.Todo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class TodoCryptoHandlerTest {

    @Test
    fun `decrypt propagates CryptoException when KMS decryption fails`() {
        val failingCrypto = object : CryptoService {
            override fun encrypt(keyName: String, plaintext: ByteArray): ByteArray = plaintext
            override fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray {
                throw CryptoException("KMS unavailable")
            }
        }
        val handler = TodoCryptoHandler(failingCrypto)
        val encrypted = "enc:${Base64.getEncoder().encodeToString("cipher".toByteArray(StandardCharsets.UTF_8))}"

        assertThrows<CryptoException> {
            handler.decrypt(encrypted)
        }
    }

    @Test
    fun `decrypt returns original value when payload after prefix is not valid Base64`() {
        val passthroughCrypto = object : CryptoService {
            override fun encrypt(keyName: String, plaintext: ByteArray): ByteArray = plaintext
            override fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray = ciphertext
        }
        val handler = TodoCryptoHandler(passthroughCrypto)
        val literalText = "enc:not valid base64!"

        assertEquals(literalText, handler.decrypt(literalText))
    }

    @Test
    fun `decryptTodo decrypts encrypted description and leaves plaintext untouched`() {
        val reversingCrypto = object : CryptoService {
            override fun encrypt(keyName: String, plaintext: ByteArray): ByteArray = plaintext.reversedArray()
            override fun decrypt(keyName: String, ciphertext: ByteArray): ByteArray = ciphertext.reversedArray()
        }
        val handler = TodoCryptoHandler(reversingCrypto)
        val encryptedDesc = handler.encrypt("secret note")
        val todo = Todo(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            title = "Test",
            description = encryptedDesc,
        )

        val decrypted = handler.decryptTodo(todo)
        assertEquals("secret note", decrypted.description)
    }
}
