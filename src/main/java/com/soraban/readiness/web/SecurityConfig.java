package com.soraban.readiness.web;

import com.soraban.readiness.security.FirmUserDetailsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two roles, split along one line: whether the action is reversible.
 *
 * <h2>What separates PREPARER from FIRM_ADMIN</h2>
 *
 * <p>{@code PREPARER} does the work &mdash; reads the dashboard, reads a client's
 * explanation, annotates an exception. {@code FIRM_ADMIN} additionally holds the actions
 * that cannot be taken back or that change what the system will do next: starting a filing
 * run, forcing a state transition, editing run configuration, and reading the audit log.
 *
 * <p>The split is by consequence, not by seniority. "Can send a form to the IRS that
 * cannot be unsent" is a meaningfully different privilege from "can look at what happened
 * last night", and a role model organised around job titles instead would put them on the
 * same side of the line.
 *
 * <h2>Isolation is NOT a role concern</h2>
 *
 * <p>Nothing here mentions firms, and that is the design. Both roles are firm-scoped by
 * the same mechanism as everything else &mdash; row-level security keyed on the firm id
 * carried by the principal. A {@code FIRM_ADMIN} has no more cross-firm reach than a
 * {@code PREPARER}: none. If tenancy were expressed as authorities, then "admin" would
 * eventually mean "sees everything", which is the failure this whole project is arranged
 * to make impossible.
 *
 * <p>So authorization answers "may this person do this", and RLS answers "whose rows are
 * these", and the two never have to agree about anything.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/app.css", "/login", "/error").permitAll()
                // Everything under /admin is irreversible or configuration-changing.
                .requestMatchers("/admin/**").hasRole("FIRM_ADMIN")
                .requestMatchers("/audit/**").hasRole("FIRM_ADMIN")
                // Default deny. Written explicitly rather than left to the framework's
                // default so that adding a controller cannot quietly add a public endpoint.
                .anyRequest().authenticated())

            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll())

            .logout(logout -> logout
                .logoutSuccessUrl("/login?logged-out")
                .permitAll())

            // Session-based, and a fresh session id on authentication. The default, stated
            // explicitly because session fixation is the one login-flow bug that leaves no
            // trace in any log.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.newSession()))

            // CSRF protection stays ON. There is exactly one state-changing endpoint on this
            // page (acknowledging an exception) and it is a POST with the token, because
            // "it's only an annotation" is how the exemption list starts.
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        // The page ships one inline <script> for its filter boxes. No
                        // external origins at all: this application should never be able to
                        // send a vendor name or a TIN fragment to a third party, and the
                        // cheapest way to guarantee that is to forbid the connection.
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")));

        return http.build();
    }

    /**
     * A delegating encoder, so the {@code {bcrypt}} prefix in {@code app_user.password_hash}
     * is what selects the algorithm. Rotating to something stronger later is then a data
     * migration rather than a code change, and old and new hashes coexist during it.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(FirmUserDetailsService users,
                                                         PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        // Report "bad credentials" for an unknown login as well as a wrong password, so
        // the form cannot be used to enumerate who works at a firm. (The provider separately
        // runs a dummy hash comparison when the user is missing, so the two paths also take
        // comparable time -- an error message that is careful while the latency is not
        // protects nothing.)
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }
}
