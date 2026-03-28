package com.onlineshopping.controller;

import com.onlineshopping.dto.AuthResponse;
import com.onlineshopping.dto.LoginRequest;
import com.onlineshopping.dto.RegisterRequest;
import com.onlineshopping.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService){
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request){
        return this.authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request){
        return this.authService.login(request);
    }
}
