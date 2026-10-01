package com.instagram.media;

/**
 * PENDING_UPLOAD -> (S3 ObjectCreated event) -> READY | FAILED.
 * Posts only publish once every attached media item is READY.
 */
public enum MediaStatus { PENDING_UPLOAD, READY, FAILED }
