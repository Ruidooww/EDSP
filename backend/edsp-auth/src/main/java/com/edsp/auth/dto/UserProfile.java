package com.edsp.auth.dto;

import java.util.List;

public record UserProfile(String username, String displayName, List<String> roles) {
}
