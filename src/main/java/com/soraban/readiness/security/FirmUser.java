package com.soraban.readiness.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal, which is also the only place a firm id may come from.
 *
 * <h2>Why the firm lives on the principal and nowhere else</h2>
 *
 * <p>The firm id is the input to every row-level-security policy in the schema. If it can
 * be influenced by anything the caller sends &mdash; a query parameter, a form field, a
 * header, a path segment &mdash; then the entire isolation model reduces to "we remembered
 * to validate it", which is exactly the class of control this project set out to replace
 * with a structural one.
 *
 * <p>Binding it to the principal makes the guarantee positional rather than procedural:
 * there is no code path that reads a firm id from a request, so there is no code path that
 * can forget to check one. The visible consequence is that an id-guessing attempt against
 * {@code /client/4711} returns "nothing to show" rather than a permission error, because
 * under RLS the row genuinely does not exist for the session &mdash; the page cannot leak
 * the difference because the difference never reached the process.
 *
 * <p>Deliberately not a {@code record}: {@link UserDetails} requires {@code getUsername()}
 * and {@code getPassword()}, and a record's generated accessors would be
 * {@code username()} and {@code password()}. More to the point, a record generates a
 * {@code toString()} that prints every field, and one of these fields is a password hash.
 * The same reasoning that shapes {@link Tin}.
 */
public final class FirmUser implements UserDetails {

    private final long userId;
    private final long firmId;
    private final String firmSlug;
    private final String username;
    private final String displayName;
    private final String role;
    private final transient String passwordHash;
    private final boolean enabled;

    public FirmUser(long userId, long firmId, String firmSlug, String username,
                    String displayName, String role, String passwordHash, boolean enabled) {
        this.userId = userId;
        this.firmId = firmId;
        this.firmSlug = firmSlug;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    public long userId()        { return userId; }
    public long firmId()        { return firmId; }
    public String firmSlug()    { return firmSlug; }
    public String displayName() { return displayName; }
    public String role()        { return role; }

    /** True for {@code FIRM_ADMIN}. The irreversible actions are gated on this. */
    public boolean isAdmin() {
        return "FIRM_ADMIN".equals(role);
    }

    /** The login identifier: {@code username@firm-slug}, since usernames are unique per firm. */
    public String loginName() {
        return username + "@" + firmSlug;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override public String getPassword()  { return passwordHash; }
    @Override public String getUsername()  { return username; }
    @Override public boolean isEnabled()   { return enabled; }

    /**
     * Prints identity, never the hash. This class ends up inside exception messages,
     * {@code MDC} maps and debug logs, and {@code toString()} is the leak path that
     * actually gets used.
     */
    @Override
    public String toString() {
        return "FirmUser[" + loginName() + " role=" + role + "]";
    }
}
