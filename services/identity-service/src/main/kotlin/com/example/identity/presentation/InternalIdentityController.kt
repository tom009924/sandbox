package com.example.identity.presentation

import com.example.identity.application.UserSettingsService
import com.example.identity.domain.model.User
import com.example.identity.domain.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class InternalUserResponse(
    val id: UUID,
    val username: String,
)

data class InternalUserCountResponse(
    val count: Long,
)

@RestController
@RequestMapping("/internal/users")
class InternalIdentityController(
    private val userRepository: UserRepository,
    private val userSettingsService: UserSettingsService,
) {
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun getUserById(@PathVariable id: UUID): ResponseEntity<InternalUserResponse> {
        val user = userRepository.findById(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(user.toInternalResponse())
    }

    @GetMapping
    @Transactional(readOnly = true)
    fun getUsersByIds(@RequestParam(required = false) ids: List<UUID>?): ResponseEntity<List<InternalUserResponse>> {
        val users = if (ids == null) {
            userRepository.findAll()
        } else {
            userRepository.findAllByIdIn(ids)
        }
        return ResponseEntity.ok(users.map { it.toInternalResponse() })
    }

    @GetMapping("/by-username")
    @Transactional(readOnly = true)
    fun getUserByUsername(@RequestParam username: String): ResponseEntity<InternalUserResponse> {
        val user = userRepository.findByUsername(username.trim()) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(user.toInternalResponse())
    }

    @GetMapping("/count")
    @Transactional(readOnly = true)
    fun countUsers(): ResponseEntity<InternalUserCountResponse> =
        ResponseEntity.ok(InternalUserCountResponse(userRepository.count()))

    @GetMapping("/{id}/settings")
    @Transactional(readOnly = true)
    fun getUserSettings(@PathVariable id: UUID): ResponseEntity<UserSettingsResponse> {
        val settings = userSettingsService.getSettings(id)
        return ResponseEntity.ok(
            UserSettingsResponse(
                userId = settings.userId,
                defaultPriority = settings.defaultPriority,
                defaultEstimatedMinutes = settings.defaultEstimatedMinutes,
                maxActiveTodos = settings.maxActiveTodos,
            ),
        )
    }

    private fun User.toInternalResponse() = InternalUserResponse(
        id = requireNotNull(id) { "User id must not be null" },
        username = username,
    )
}
