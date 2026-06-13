package com.vertex.profile.web.dto;

import com.vertex.profile.domain.ProfileVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body for creating or replacing the caller's own profile. */
public record UpsertProfileRequest(
        @Size(max = 100) String displayName,
        @Size(max = 500) String bio,
        @Size(max = 500) String avatarUrl,
        @Size(max = 100) String location,
        @NotNull ProfileVisibility visibility
) {
}
