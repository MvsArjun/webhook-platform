package dev.relay.identity;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Spring's User erases its password hash after successful authentication. */
public final class AccountPrincipal extends User {
    @Serial
    private static final long serialVersionUID = 1L;
    private final UUID id;

    public AccountPrincipal(UUID id, String email, String passwordHash) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT")));
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    @Override
    public String toString() {
        return "AccountPrincipal[id=" + id + "]";
    }
}
