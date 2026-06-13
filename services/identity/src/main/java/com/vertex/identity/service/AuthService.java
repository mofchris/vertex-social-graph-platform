package com.vertex.identity.service;

import com.vertex.identity.config.JwtProperties;
import com.vertex.identity.domain.RefreshToken;
import com.vertex.identity.domain.User;
import com.vertex.identity.domain.UserStatus;
import com.vertex.identity.exception.DuplicateResourceException;
import com.vertex.identity.exception.InvalidCredentialsException;
import com.vertex.identity.exception.InvalidTokenException;
import com.vertex.identity.repository.RefreshTokenRepository;
import com.vertex.identity.repository.UserRepository;
import com.vertex.identity.security.JwtService;
import com.vertex.identity.web.dto.AuthResponse;
import com.vertex.identity.web.dto.LoginRequest;
import com.vertex.identity.web.dto.SignupRequest;
import com.vertex.identity.web.dto.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProps) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        // Friendly pre-check, but the DB unique constraint is what actually wins the race.
        if (users.existsByEmail(req.email())) {
            throw new DuplicateResourceException("email already in use");
        }
        if (users.existsByUsername(req.username())) {
            throw new DuplicateResourceException("username already taken");
        }
        User user = new User(
                req.email(),
                req.username(),
                passwordEncoder.encode(req.password()),
                req.displayName());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent signups both passed the pre-check; the constraint caught the loser.
            throw new DuplicateResourceException("email or username already taken");
        }
        return issueTokens(user, UUID.randomUUID());
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmailAndStatus(req.email(), UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidCredentialsException("invalid email or password"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("invalid email or password");
        }
        return issueTokens(user, UUID.randomUUID());
    }

    // noRollbackFor: on reuse detection we revoke the whole family and then reject the
    // request. Without this, throwing would roll back the revocation we just made.
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResponse refresh(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("invalid refresh token"));

        if (token.isRevoked()) {
            // A revoked token presented again means it was stolen or replayed. Burn the family.
            refreshTokens.revokeFamily(token.getFamilyId());
            throw new InvalidTokenException("refresh token reuse detected");
        }
        if (token.isExpired()) {
            throw new InvalidTokenException("refresh token expired");
        }

        User user = users.findByIdAndStatus(token.getUserId(), UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidTokenException("account not available"));

        // Rotate: revoke the presented token, mint a fresh one in the same family.
        token.setRevoked(true);
        IssuedRefresh next = createRefresh(user, token.getFamilyId());
        token.setReplacedBy(next.entity().getId());

        String access = jwtService.issueAccessToken(user);
        return AuthResponse.of(access, next.raw(), jwtService.accessTtlSeconds(), UserResponse.from(user));
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(sha256(rawToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId()));
    }

    @Transactional(readOnly = true)
    public User requireActiveUser(UUID id) {
        return users.findByIdAndStatus(id, UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidTokenException("account not available"));
    }

    private AuthResponse issueTokens(User user, UUID familyId) {
        String access = jwtService.issueAccessToken(user);
        IssuedRefresh refresh = createRefresh(user, familyId);
        return AuthResponse.of(access, refresh.raw(), jwtService.accessTtlSeconds(), UserResponse.from(user));
    }

    private IssuedRefresh createRefresh(User user, UUID familyId) {
        String raw = newRawToken();
        RefreshToken rt = new RefreshToken(
                user.getId(),
                sha256(raw),
                familyId,
                Instant.now().plus(jwtProps.refreshTtl()));
        refreshTokens.save(rt);
        return new IssuedRefresh(raw, rt);
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record IssuedRefresh(String raw, RefreshToken entity) {
    }
}
