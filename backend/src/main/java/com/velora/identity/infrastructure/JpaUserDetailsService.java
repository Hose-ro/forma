package com.velora.identity.infrastructure;

import com.velora.shared.security.AuthUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public JpaUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .map(u -> new AuthUser(u.getId(), u.getEmail(), u.getPasswordHash(), u.getRole().name(), u.isEnabled()))
                .orElseThrow(() -> new UsernameNotFoundException("Not found"));
    }
}
