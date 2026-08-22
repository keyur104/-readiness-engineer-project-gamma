package com.soraban.readiness.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Renders the login form.
 *
 * <p>A controller rather than a {@code WebMvcConfigurer#addViewControllers} entry, purely
 * so the route is discoverable by searching for the URL. There is no logic here on purpose:
 * the form posts to Spring Security's own filter, so this application never handles a
 * plaintext password, never writes one to a field, and has no code path that could log one.
 */
@Controller
@ConditionalOnProperty(name = "readiness.database.enabled", havingValue = "true", matchIfMissing = true)
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
