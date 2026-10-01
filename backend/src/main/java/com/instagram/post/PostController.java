package com.instagram.post;

import com.instagram.post.PostDtos.CreatePostRequest;
import com.instagram.post.PostDtos.PostResponse;
import com.instagram.security.AuthenticatedUser;
import com.instagram.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
@Tag(name = "Posts")
public class PostController {
    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a post from uploaded media. Status is PUBLISHED or PROCESSING.")
    public PostResponse create(@CurrentUser AuthenticatedUser user, @Valid @RequestBody CreatePostRequest req) {
        return postService.create(user.userId(), req);
    }

    @GetMapping("/{postId}")
    public PostResponse get(@CurrentUser AuthenticatedUser user, @PathVariable String postId) {
        return postService.get(user.userId(), postId);
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthenticatedUser user, @PathVariable String postId) {
        postService.delete(user.userId(), postId);
    }
}
