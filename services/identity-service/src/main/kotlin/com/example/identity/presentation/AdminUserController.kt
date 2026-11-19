package com.example.identity.presentation

import com.example.identity.application.AdminUserService
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AdminUserResponse(
    val id: UUID,
    val username: String,
)

@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(
    private val adminUserService: AdminUserService,
) {
    @GetMapping
    @Transactional(readOnly = true)
    fun getAdminUsers(): ResponseEntity<List<AdminUserResponse>> {
        val users = adminUserService.getUsers().map { user ->
            AdminUserResponse(
                id = requireNotNull(user.id) { "User ID must not be null" },
                username = user.username,
            )
        }
        return ResponseEntity.ok(users)
    }
}
