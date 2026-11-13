package com.famlog.auth.service;

import com.famlog.user.domain.User;

public record AuthResult(TokenPair tokens, User user) {
}
