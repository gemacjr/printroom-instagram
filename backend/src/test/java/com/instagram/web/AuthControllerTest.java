package com.instagram.web;

import com.instagram.common.ApiException;
import com.instagram.user.AuthController;
import com.instagram.user.AuthService;
import com.instagram.user.UserDtos.AuthResponse;
import com.instagram.user.UserDtos.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(WebSliceConfig.class)
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;

    @Test
    void registerIsPublicAndReturns201() throws Exception {
        var user = new UserResponse("u1", "alice", "Alice", "", 0, 0, 0, 0, null);
        when(authService.register(any())).thenReturn(new AuthResponse("tok", 3600, user));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","email":"a@example.com","password":"password123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok"))
                .andExpect(jsonPath("$.user.username").value("alice"))
                // null isFollowing omitted by NON_NULL inclusion
                .andExpect(jsonPath("$.user.isFollowing").doesNotExist());
    }

    @Test
    void registerValidatesFields() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Bad Name!","email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void domainErrorsMapToStatusAndCode() throws Exception {
        when(authService.login(any())).thenThrow(ApiException.unauthorized("Invalid username or password"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"b\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest());
    }
}
