package com.novel.ai.config;
import com.novel.common.security.RestAccessDeniedHandler;
import com.novel.common.security.RestAuthenticationEntryPoint;
import com.novel.common.security.filter.HeaderAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Configuration @EnableMethodSecurity @RequiredArgsConstructor
public class AiSecurityConfig {
 private final RestAuthenticationEntryPoint authenticationEntryPoint;
 private final RestAccessDeniedHandler accessDeniedHandler;
 private final HeaderAuthenticationFilter headerAuthenticationFilter;
 @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
  return http.csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
   .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
    .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
    .requestMatchers("/doc.html","/webjars/**","/v3/api-docs/**","/swagger-ui/**","/favicon.ico","/error").permitAll()
    .requestMatchers("/internal/**").permitAll()
    .requestMatchers("/api/admin/**").hasRole("ADMIN")
    .requestMatchers("/api/ai","/api/ai/**").hasAnyRole("AUTHOR","ADMIN")
    .anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
   .addFilterBefore(headerAuthenticationFilter,UsernamePasswordAuthenticationFilter.class).build();
 }
 @Bean public FilterRegistrationBean<HeaderAuthenticationFilter> headerFilterRegistration(HeaderAuthenticationFilter filter) {
  FilterRegistrationBean<HeaderAuthenticationFilter> r=new FilterRegistrationBean<>(filter);r.setEnabled(false);return r;
 }
}
