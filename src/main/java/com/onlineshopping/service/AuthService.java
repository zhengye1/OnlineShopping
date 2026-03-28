package com.onlineshopping.service;

import com.onlineshopping.dto.AuthResponse;
import com.onlineshopping.dto.LoginRequest;
import com.onlineshopping.dto.RegisterRequest;
import com.onlineshopping.enums.Role;
import com.onlineshopping.model.User;
import com.onlineshopping.exception.BadRequestException;
import com.onlineshopping.repository.UserRepository;
import com.onlineshopping.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        // Check if username already exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BadRequestException("Username already exists");
        }

        // Check if email already exists
        if (request.getEmail() != null && userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already exists");
        }

        // Create user with hashed password
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));  // BCrypt hash!
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole(Role.BUYER);  // Default role
        user.setActive(true);

        userRepository.save(user);

        // Generate JWT token
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        return new AuthResponse(token, user.getUsername(), user.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        // Find user
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadRequestException("Invalid username or password"));

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadRequestException("Invalid username or password");
        }

        // Check if active
        if (!user.isActive()) {
            throw new BadRequestException("Account is deactivated");
        }

        // Generate JWT token
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        return new AuthResponse(token, user.getUsername(), user.getRole().name());
    }
}
