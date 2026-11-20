package com.olvenio.identity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "first_name", nullable = false)
    private String firstName;
    @Column(name = "last_name", nullable = false)
    private String lastName;
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;
    @Column(name = "terms_accepted_at", updatable = false)
    private Instant termsAcceptedAt;
    @Column(name = "terms_version", length = 32, updatable = false)
    private String termsVersion;
    @ElementCollection
    @CollectionTable(name = "user_category_interests", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "category_id")
    private Set<UUID> categoryInterests = new HashSet<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected User() {
    }

    public User(String email, String passwordHash, String firstName, String lastName, String phone, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.firstName = firstName.trim();
        this.lastName = lastName.trim();
        this.phone = phone;
        this.role = role;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void updateProfile(String firstName, String lastName, String phone) {
        if (firstName != null) this.firstName = firstName.trim();
        if (lastName != null) this.lastName = lastName.trim();
        if (phone != null) this.phone = phone.isBlank() ? null : phone.trim();
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Proven by a link sent to the address (verification or password reset). */
    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) emailVerifiedAt = now;
    }

    public void acceptTerms(String version, Instant now) {
        termsAcceptedAt = now;
        termsVersion = version;
    }

    public void updateCategoryInterests(Set<UUID> ids) {
        categoryInterests.retainAll(ids);
        categoryInterests.addAll(ids);
    }

    public Set<UUID> getCategoryInterests() { return Set.copyOf(categoryInterests); }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getPhone() { return phone; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
