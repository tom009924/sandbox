package com.example.identity.application

import com.example.identity.domain.model.User
import com.example.identity.domain.repository.UserRepository

class AdminUserService(
    private val userRepository: UserRepository,
) {
    fun getUsers(): List<User> = userRepository.findAll()
}
