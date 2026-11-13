package com.famlog.support.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;

/** JwtAuthenticationTest용: 인증이 필요한 경로에서 AuthUser 파라미터가 채워지는지 돌려준다. */
@RestController
public class AuthTestController {

    public static final String ME = "/api/v1/_test/me";

    @GetMapping(ME)
    AuthUser me(AuthUser user) {
        return user;
    }
}
