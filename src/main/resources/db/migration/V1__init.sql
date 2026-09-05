CREATE TABLE users (
    user_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id    VARCHAR(255) NOT NULL,
    username   VARCHAR(255),
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME,
    CONSTRAINT uk_users_chat_id UNIQUE (chat_id)
);

CREATE TABLE movies (
    movie_id     VARCHAR(255) PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    mov_no       VARCHAR(255),
    release_date DATE,
    status       VARCHAR(255)
);

CREATE TABLE theaters (
    theater_id  VARCHAR(255) PRIMARY KEY,
    brand       VARCHAR(255) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    site_no     VARCHAR(255),
    region      VARCHAR(255)
);

CREATE TABLE screen_types (
    screen_type_id INT AUTO_INCREMENT PRIMARY KEY,
    type_name      VARCHAR(255) NOT NULL,
    CONSTRAINT uk_screen_types_type_name UNIQUE (type_name)
);

CREATE TABLE schedules (
    schedule_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id       VARCHAR(255),
    theater_id     VARCHAR(255),
    screen_type_id INT,
    show_date      DATE,
    show_time      TIME,
    is_opened      BOOLEAN NOT NULL DEFAULT FALSE,
    crawled_at     DATETIME,
    CONSTRAINT fk_schedules_movie FOREIGN KEY (movie_id) REFERENCES movies (movie_id),
    CONSTRAINT fk_schedules_theater FOREIGN KEY (theater_id) REFERENCES theaters (theater_id),
    CONSTRAINT fk_schedules_screen_type FOREIGN KEY (screen_type_id) REFERENCES screen_types (screen_type_id),
    CONSTRAINT uk_schedules_slot UNIQUE (movie_id, theater_id, screen_type_id, show_date, show_time)
);

CREATE TABLE notification_subscriptions (
    subscription_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT,
    movie_id        VARCHAR(255),
    theater_id      VARCHAR(255),
    screen_type_id  INT,
    target_date     DATE,
    is_triggered    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      DATETIME,
    CONSTRAINT fk_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_subscriptions_movie FOREIGN KEY (movie_id) REFERENCES movies (movie_id),
    CONSTRAINT fk_subscriptions_theater FOREIGN KEY (theater_id) REFERENCES theaters (theater_id),
    CONSTRAINT fk_subscriptions_screen_type FOREIGN KEY (screen_type_id) REFERENCES screen_types (screen_type_id)
);

CREATE TABLE notification_histories (
    history_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    subscription_id BIGINT,
    sent_at         DATETIME,
    status          VARCHAR(255),
    CONSTRAINT fk_histories_subscription FOREIGN KEY (subscription_id) REFERENCES notification_subscriptions (subscription_id)
);
