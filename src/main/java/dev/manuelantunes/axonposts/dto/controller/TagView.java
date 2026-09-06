package dev.manuelantunes.axonposts.dto.controller;

/** DTO de saída de uma tag no GraphQL: o {@code type Tag} do schema. */
public record TagView(String id, String name) {
}
