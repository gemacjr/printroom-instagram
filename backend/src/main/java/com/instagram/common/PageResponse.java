package com.instagram.common;

import java.util.List;

/** Cursor-paginated response. {@code nextCursor} is null when there are no more items. */
public record PageResponse<T>(List<T> items, String nextCursor) {}
