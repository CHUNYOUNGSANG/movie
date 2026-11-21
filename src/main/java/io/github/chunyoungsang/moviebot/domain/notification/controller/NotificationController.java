package io.github.chunyoungsang.moviebot.domain.notification.controller;

import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationCreateRequest;
import io.github.chunyoungsang.moviebot.domain.notification.dto.NotificationResponse;
import io.github.chunyoungsang.moviebot.domain.notification.entity.NotificationSubscription;
import io.github.chunyoungsang.moviebot.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 로그인한 사용자 본인의 알림만 다룬다 (Authorization: Bearer {토큰}, POST /api/auth/telegram 으로 발급).
 * 사용자는 토큰의 subject(텔레그램 id = chatId)로 정하고, 요청에 적힌 chatId 는 쓰지 않는다.
 *
 * POST   /api/notifications           알림 신청 등록
 * GET    /api/notifications           내 알림 목록
 * DELETE /api/notifications/{id}      내 알림 신청 취소
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<NotificationResponse> register(@RequestBody NotificationCreateRequest request,
                                                         Authentication authentication) {
        NotificationSubscription created = notificationService.register(request.forUser(authentication.getName()));
        return ResponseEntity.ok(NotificationResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> findMine(Authentication authentication) {
        return ResponseEntity.ok(notificationService.findByChatId(authentication.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable Long id, Authentication authentication) {
        notificationService.cancel(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
