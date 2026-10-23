package com.example.todo.domain.exception

class UserAlreadyExistsException(val username: String) :
    RuntimeException("User with username '$username' already exists")

