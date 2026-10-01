package com.instagram.security;

/** Principal stored in the SecurityContext for authenticated requests. */
public record AuthenticatedUser(String userId, String username) {}
