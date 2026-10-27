package dev.relay.identity;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public AccountView register(String email, String password) {
        if (!PasswordConfiguration.withinByteLimit(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid password length");
        }
        UUID id = UUID.randomUUID();
        String normalizedEmail = normalizeEmail(email);
        String encodedPassword = passwords.encode(password);
        try {
            // A single autocommitted insert: hashing does not occupy a DB transaction.
            jdbc.update("INSERT INTO accounts (id, email, password_hash) VALUES (?, ?, ?)",
                    id, normalizedEmail, encodedPassword);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account already exists");
        }
        return new AccountView(id, normalizedEmail);
    }

    @Override
    public AccountPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.length() > 320) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return jdbc.query("SELECT id, email, password_hash FROM accounts WHERE email = ?",
                        (row, index) -> new AccountPrincipal(row.getObject("id", UUID.class),
                                row.getString("email"), row.getString("password_hash")), normalizeEmail(username))
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
