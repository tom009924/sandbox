package com.example.todo.domain.security

interface PasswordEncoder {
    fun encode(rawPassword: String): String

    fun matches(rawPassword: String, encodedPassword: String): Boolean
}

