package com.example.Scenith.config;

import com.example.Scenith.security.JwtFilter;
import com.example.Scenith.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


import java.util.List;


@Configuration
@Profile("!test") // Only active in non-test profiles
public class SecurityConfig implements WebMvcConfigurer {
    private final JwtUtil jwtUtil;


    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "https://scenith.in"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", config);
        return source;
    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(List.of("http://localhost:3000", "https://scenith.in"));
                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
                    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
                    config.setExposedHeaders(List.of("Authorization"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll() // Allow health checks
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/test-db").permitAll() // Allow public access to /test-db
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/api/global-elements", "/api/global-elements/**").permitAll() // Public access
                        .requestMatchers("/api/image-editor/elements").permitAll()  // Add this line
                        .requestMatchers("/api/image-editor/elements/**").permitAll() // Add this line
                        .requestMatchers("/projects/{projectId}/waveforms/{filename}").permitAll()
                        .requestMatchers("/developer/**").authenticated() // Requires JWT, role checked in JwtFilter
                        .requestMatchers("/videos/upload", "/videos/my-videos", "/videos/merge", "/videos/edited-videos","/videos/trim", "/videos/split", "/videos/duration/**").authenticated()
                        .requestMatchers("/projects/{projectId}/images/{filename}").permitAll() // Place this BEFORE the general /projects/** rule
                        .requestMatchers("image/projects/{projectId}/{filename}").permitAll() // Place this BEFORE the general /projects/** rule
                        .requestMatchers("/projects/{projectId}/audio/{filename}").permitAll()
                        .requestMatchers("audio/projects/{projectId}/{filename}").permitAll()
                        .requestMatchers("/projects/{projectId}/videos/{filename}").permitAll()
                        .requestMatchers("videos/projects/{projectId}/{filename}").permitAll()
                        .requestMatchers("projects/{projectId}/export").permitAll()
                        .requestMatchers("project/export-links").permitAll()
                        .requestMatchers("/api/ai-voices/get-all-voices").permitAll()
                        .requestMatchers("/api/ai-voices/voices-by-language").permitAll()
                        .requestMatchers("/api/ai-voices/voices-by-gender").permitAll()
                        .requestMatchers("/api/ai-voices/voices-by-language-and-gender").permitAll()
                        .requestMatchers("/projects/**", "/projects/{projectId}/add-to-timeline").authenticated()
                        .requestMatchers(HttpMethod.GET, "/videos/edited-videos/**").permitAll()
                        .requestMatchers("/videos/**", "/videos/*").permitAll()  // ✅ Allow public video access
                        .anyRequest().authenticated()
                )
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);


        return http.build();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:3000", "https://scenith.in")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS","PATCH")
                .allowedHeaders("*")
                .allowCredentials(true);
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }


    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}