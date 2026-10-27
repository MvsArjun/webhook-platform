package dev.relay.identity;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class AuthenticationController {
    private final AccountService accounts;

    public AuthenticationController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/auth/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<AccountView> register(@Valid @RequestBody Registration request) {
        var account = accounts.register(request.email(), request.password());
        return ResponseEntity.created(URI.create("/api/auth/me")).body(account);
    }

    @GetMapping("/api/auth/me")
    public AccountView me(@AuthenticationPrincipal AccountPrincipal account) {
        return new AccountView(account.id(), account.getUsername());
    }

    public record CsrfView(String headerName, String parameterName, String token) {
        @Override
        public String toString() {
            return "CsrfView[token=REDACTED]";
        }
    }

    public record Registration(@NotBlank @Email @Size(max = 320) String email,
                               @NotBlank @Size(min = 12, max = 72) String password) {
        public Registration {
            email = AccountService.normalizeEmail(email);
        }

        @Override
        public String toString() {
            return "Registration[REDACTED]";
        }
    }
}
