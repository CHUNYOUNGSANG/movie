-- 보관 기간이 지난 상영 일정 삭제용 (show_date < 기준일). 유니크 키(movie_id, ...)는 앞 컬럼이 달라 범위 조회에 못 쓴다
CREATE INDEX idx_schedules_show_date ON schedules (show_date);
