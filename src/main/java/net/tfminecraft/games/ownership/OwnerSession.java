package net.tfminecraft.games.ownership;

import java.util.UUID;

/** Local token keeps optional character API types outside ordinary table classes. */
public record OwnerSession(UUID account, UUID character, long generation) {}
