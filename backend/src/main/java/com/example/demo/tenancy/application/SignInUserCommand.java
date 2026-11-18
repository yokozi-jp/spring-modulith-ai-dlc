package com.example.demo.tenancy.application;

/**
 * IdP でログインした利用者をアプリの利用者に対応づけるユースケースの入力。
 *
 * @param issuer ID トークンの {@code iss}
 * @param subject ID トークンの {@code sub}
 */
public record SignInUserCommand(String issuer, String subject) {}
