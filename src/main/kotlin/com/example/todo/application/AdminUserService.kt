package com.example.todo.application

import com.example.todo.domain.model.User
import com.example.todo.domain.repository.UserRepository

class AdminUserService(
    private val userRepository: UserRepository,
) {
    fun getUsers(): List<User> = userRepository.findAll()
}

