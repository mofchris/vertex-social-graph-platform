package com.vertex.profile.domain;

/** Who can view a profile. FRIENDS will consult the Graph service once it exists. */
public enum ProfileVisibility {
    PUBLIC,
    FRIENDS,
    PRIVATE
}
