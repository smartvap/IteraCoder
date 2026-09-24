-- H2 兼容的 DDL（无 MySQL 时自动创建）
CREATE TABLE IF NOT EXISTS tb_user (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    user_name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    phone VARCHAR(255) NOT NULL,
    sex VARCHAR(255) NOT NULL,
    id_number VARCHAR(255) NOT NULL,
    status INT DEFAULT 1,
    create_time DATE,
    update_time DATE,
    create_user BIGINT,
    update_user BIGINT
);

CREATE TABLE IF NOT EXISTS tb_user_config (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    config_json TEXT,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS log_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    method_name VARCHAR(255),
    class_name VARCHAR(255),
    request_time DATE,
    request_params TEXT,
    response TEXT
);

CREATE TABLE IF NOT EXISTS token_usage_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    ip_address VARCHAR(45) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    request_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_user_ip_time ON token_usage_detail (user_id, ip_address, request_time);
CREATE INDEX IF NOT EXISTS idx_ip_time ON token_usage_detail (ip_address, request_time);
CREATE INDEX IF NOT EXISTS idx_request_time ON token_usage_detail (request_time);

CREATE TABLE IF NOT EXISTS token_usage_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_type VARCHAR(20) NOT NULL,
    stat_key VARCHAR(100) NOT NULL,
    stat_date DATE NOT NULL,
    total_requests INT NOT NULL DEFAULT 0,
    total_prompt_tokens BIGINT NOT NULL DEFAULT 0,
    total_completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    UNIQUE (stat_type, stat_key, stat_date)
);

CREATE TABLE IF NOT EXISTS model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    ip_address VARCHAR(45) NOT NULL,
    config_name VARCHAR(100) NOT NULL,
    model_type VARCHAR(20) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    base_url VARCHAR(500) NULL,
    api_key VARCHAR(500) NULL,
    temperature DOUBLE DEFAULT 0.7,
    max_tokens INT DEFAULT 4096,
    is_active TINYINT DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_model_user_ip ON model_config (user_id, ip_address);
CREATE INDEX IF NOT EXISTS idx_model_ip ON model_config (ip_address);

CREATE TABLE IF NOT EXISTS conversation_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    model_name VARCHAR(64) NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conv_session_id ON conversation_record (session_id);
CREATE INDEX IF NOT EXISTS idx_conv_user_id ON conversation_record (user_id);

ALTER TABLE token_usage_detail ADD COLUMN IF NOT EXISTS source VARCHAR(32) DEFAULT 'chat';
ALTER TABLE token_usage_detail ADD COLUMN IF NOT EXISTS step_name VARCHAR(100) DEFAULT NULL;

CREATE TABLE IF NOT EXISTS daily_token_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_date DATE NOT NULL,
    total_requests INT NOT NULL DEFAULT 0,
    total_prompt_tokens BIGINT NOT NULL DEFAULT 0,
    total_completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    total_users INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (stat_date)
);

CREATE TABLE IF NOT EXISTS workflow_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    thread_id VARCHAR(128) NOT NULL,
    round INT DEFAULT 1,
    requirement TEXT,
    status VARCHAR(32),
    decomposition_result TEXT,
    reasoning_result TEXT,
    review_decision VARCHAR(32),
    review_comment TEXT,
    codegen_files TEXT,
    prompt_tokens BIGINT DEFAULT 0,
    completion_tokens BIGINT DEFAULT 0,
    total_duration_ms BIGINT DEFAULT 0,
    workflow_message TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wf_session_id ON workflow_record (session_id);
CREATE INDEX IF NOT EXISTS idx_wf_thread_id ON workflow_record (thread_id);
