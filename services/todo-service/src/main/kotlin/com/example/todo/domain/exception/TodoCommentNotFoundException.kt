package com.example.todo.domain.exception

import java.util.UUID

class TodoCommentNotFoundException(commentId: UUID) :
    RuntimeException("Comment $commentId not found")
