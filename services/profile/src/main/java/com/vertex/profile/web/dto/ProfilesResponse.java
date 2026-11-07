package com.vertex.profile.web.dto;

import java.util.List;

/** A batch of profiles; hidden and missing profiles are omitted rather than stubbed. */
public record ProfilesResponse(
        List<ProfileResponse> items
) {
}
