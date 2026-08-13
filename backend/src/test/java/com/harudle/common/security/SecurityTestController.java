package com.harudle.common.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SecurityTestController {

    @GetMapping("/api/v1/test-auth")
    String authenticated() {
        return "authenticated";
    }

    @GetMapping("/api/v1/public/test")
    String publicEndpoint() {
        return "public";
    }
}
