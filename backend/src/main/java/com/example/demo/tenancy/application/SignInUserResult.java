package com.example.demo.tenancy.application;

/**
 * IdP でログインした利用者をアプリの利用者に対応づけるユースケースの結果。
 *
 * @param userId アプリの利用者の ID
 */
public record SignInUserResult(String userId) {}
