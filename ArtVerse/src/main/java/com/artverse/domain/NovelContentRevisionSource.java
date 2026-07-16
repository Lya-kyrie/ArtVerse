package com.artverse.domain;

/** Describes how a saved novel-content revision was produced. */
public enum NovelContentRevisionSource {
    MANUAL,
    AI,
    RESTORE,
    LEGACY_IMPORT,
    GENERATED
}
