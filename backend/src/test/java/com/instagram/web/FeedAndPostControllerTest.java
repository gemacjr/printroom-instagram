package com.instagram.web;

import com.instagram.common.PageResponse;
import com.instagram.feed.FeedController;
import com.instagram.feed.FeedService;
import com.instagram.post.PostController;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.post.PostService;
import com.instagram.post.PostStatus;
import com.instagram.security.JwtService;
import com.instagram.user.UserDtos.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FeedController.class, PostController.class})
@Import(WebSliceConfig.class)
class FeedAndPostControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean FeedService feedService;
    @MockitoBean PostService postService;

    private String bearer() {
        return "Bearer " + jwt.issue("u1", "alice");
    }

    @Test
    void feedRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/feed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mvc.perform(get("/api/feed").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void feedPassesPrincipalCursorAndLimit() throws Exception {
        var post = new PostResponse("p1", new UserSummary("u2", "bob", "Bob"), "hi", PostStatus.PUBLISHED,
                List.of(), 1, 1L);
        when(feedService.getFeed("u1", "abc", 5)).thenReturn(new PageResponse<>(List.of(post), "next"));

        mvc.perform(get("/api/feed").param("cursor", "abc").param("limit", "5").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postId").value("p1"))
                .andExpect(jsonPath("$.items[0].author.username").value("bob"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    void createPostValidatesMediaIds() throws Exception {
        mvc.perform(post("/api/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caption\":\"x\",\"mediaIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.mediaIds").exists());
    }

    @Test
    void createPostReturns201() throws Exception {
        var created = new PostResponse("p1", new UserSummary("u1", "alice", "Alice"), "x",
                PostStatus.PROCESSING, List.of(), 1, null);
        when(postService.create(eq("u1"), any())).thenReturn(created);

        mvc.perform(post("/api/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caption\":\"x\",\"mediaIds\":[\"m1\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        verify(postService).create(eq("u1"), any());
    }
}
