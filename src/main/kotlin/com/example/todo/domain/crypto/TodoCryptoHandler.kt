package com.example.todo.domain.crypto

import com.example.todo.domain.model.Todo
import java.nio.charset.StandardCharsets
import java.util.Base64

class TodoCryptoHandler(
    private val cryptoService: CryptoService? = null,
    private val kmsKeyName: String = DEFAULT_KMS_KEY,
) {
    fun encrypt(description: String?): String? {
        if (description == null || cryptoService == null) return description
        val encrypted = cryptoService.encrypt(kmsKeyName, description.toByteArray(StandardCharsets.UTF_8))
        return "$ENCRYPTED_PREFIX${Base64.getEncoder().encodeToString(encrypted)}"
    }

    fun decrypt(description: String?): String? = when {
        description == null || cryptoService == null -> description
        !description.startsWith(ENCRYPTED_PREFIX) -> description
        else -> try {
            val cipherBytes = Base64.getDecoder().decode(description.removePrefix(ENCRYPTED_PREFIX))
            String(cryptoService.decrypt(kmsKeyName, cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            description
        }
    }

    fun decryptTodo(todo: Todo): Todo =
        if (todo.description?.startsWith(ENCRYPTED_PREFIX) == true) {
            todo.copy(description = decrypt(todo.description))
        } else {
            todo
        }

    companion object {
        const val DEFAULT_KMS_KEY = "projects/todo/keys/task-key"
        private const val ENCRYPTED_PREFIX = "enc:"
    }
}
