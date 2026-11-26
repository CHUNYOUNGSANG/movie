-- 부하 테스트 데이터: 사용자 @users 명이 영화 @movies 편에 나눠 신청 (지점·관 없음 → 가짜 크롤러 결과와 모두 매칭)
-- 크롤링 그룹 수 = @movies, 발송할 알림 수 = @users
-- 사용법: loadtest/README.md
SET @users  = COALESCE(@users, 1000);
SET @movies = COALESCE(@movies, 20);
SET @date   = COALESCE(@date, CURDATE() + INTERVAL 7 DAY);
SET SESSION cte_max_recursion_depth = 1000000;

INSERT INTO movies (movie_id, title)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @movies)
SELECT CONCAT('부하테스트 영화 ', n), CONCAT('부하테스트 영화 ', n) FROM seq
ON DUPLICATE KEY UPDATE title = VALUES(title);

INSERT INTO users (chat_id, username, active, created_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @users)
SELECT CONCAT('lt-', n), CONCAT('loadtest', n), TRUE, NOW() FROM seq
ON DUPLICATE KEY UPDATE active = TRUE;

INSERT INTO notification_subscriptions (user_id, movie_id, target_date, is_triggered, created_at)
SELECT u.user_id, CONCAT('부하테스트 영화 ', (CAST(SUBSTRING(u.chat_id, 4) AS UNSIGNED) % @movies) + 1), @date, FALSE, NOW()
FROM users u WHERE u.chat_id LIKE 'lt-%';

SELECT COUNT(*) AS pending_subscriptions FROM notification_subscriptions WHERE is_triggered = FALSE;
