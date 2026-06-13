package com.vertex.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$",
                message = "username must be 3-50 chars: letters, digits, or underscore") String username,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 100) String displayName
) {
}
