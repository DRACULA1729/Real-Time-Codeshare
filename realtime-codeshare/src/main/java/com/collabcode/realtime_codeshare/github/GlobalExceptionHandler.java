package com.collabcode.realtime_codeshare.github;

import com.collabcode.realtime_codeshare.controller.GitHubController;
import com.collabcode.realtime_codeshare.controller.SessionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates exceptions from the JSON API controllers into clean {@code {error, status}}
 * responses. Never leaks a stack trace or an access token to the client.
 *
 * Scoped to the API controllers so it does not turn framework-level conditions
 * (e.g. a missing static resource, which should be a 404) into 500s.
 */
@RestControllerAdvice(assignableTypes = {
        GitHubController.class, GitHubOAuthController.class, SessionController.class})
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<?> handleGitHubApi(GitHubApiException ex) {
        return body(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(GitHubAuthException.class)
    public ResponseEntity<?> handleGitHubAuth(GitHubAuthException ex) {
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatus(ResponseStatusException ex) {
        return body(ex.getStatusCode(), ex.getReason() == null ? "request failed" : ex.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, msg.isBlank() ? "validation failed" : msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleOther(Exception ex) {
        log.error("Unhandled exception", ex); // server-side only
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private ResponseEntity<?> body(HttpStatusCode status, String error) {
        return ResponseEntity.status(status).body(Map.of("error", error, "status", status.value()));
    }
}
