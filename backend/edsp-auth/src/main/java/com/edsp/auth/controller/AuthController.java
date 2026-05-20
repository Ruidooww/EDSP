package com.edsp.auth.controller;

import com.edsp.auth.dto.LoginRequest;
import com.edsp.auth.dto.LoginResponse;
import com.edsp.auth.dto.UserProfile;
import com.edsp.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final String demoUsername;
    private final String demoPassword;

    public AuthController(
            @Value("${edsp.auth.demo.username:${EDSP_ADMIN_USERNAME:admin}}") String demoUsername,
            @Value("${edsp.auth.demo.password:${EDSP_ADMIN_PASSWORD:Admin@123}}") String demoPassword) {
        this.demoUsername = demoUsername;
        this.demoPassword = demoPassword;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        if (!demoUsername.equals(request.username()) || !demoPassword.equals(request.password())) {
            return ApiResponse.fail("账号或密码错误");
        }
        var raw = request.username() + ":" + Instant.now();
        var token = Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return ApiResponse.ok(new LoginResponse(token, "Bearer", 7200));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(new UserProfile(demoUsername, "平台管理员", List.of("ADMIN")));
    }
}
