package domain.notification.controller;

import domain.notification.dto.NotificationRegisterRequest;
import domain.notification.entity.NotificationRequest;
import domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * POST   /api/notifications           알림 신청 등록
 * GET    /api/notifications           전체 목록
 * DELETE /api/notifications/{id}      알림 신청 취소
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<NotificationRequest> register(
            @RequestBody NotificationRegisterRequest request) {
        return ResponseEntity.ok(notificationService.register(request));
    }

    @GetMapping
    public ResponseEntity<List<NotificationRequest>> findAll() {
        return ResponseEntity.ok(notificationService.findAll());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        notificationService.cancel(id);
        return ResponseEntity.noContent().build();
    }
}