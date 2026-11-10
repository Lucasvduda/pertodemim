package br.com.pertodemim.security;

/** Identidade autenticada extraída do JWT. */
public record UsuarioPrincipal(Long id, String email) {
}
