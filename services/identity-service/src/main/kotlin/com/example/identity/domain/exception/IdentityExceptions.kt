package com.example.identity.domain.exception

import java.util.UUID

class UserAlreadyExistsException(username: String) :
    RuntimeException("User with username '$username' already exists")

class UserNotFoundException(id: UUID) :
    RuntimeException("User not found: $id")

class InvalidCredentialsException :
    RuntimeException("Invalid username or password")
