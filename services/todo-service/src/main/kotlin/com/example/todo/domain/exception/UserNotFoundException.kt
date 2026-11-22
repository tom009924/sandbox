package com.example.todo.domain.exception

class UserNotFoundException(username: String) : RuntimeException("User '$username' not found")
