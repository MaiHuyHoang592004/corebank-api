create table if not exists app_health_check (
    id bigserial primary key,
    note varchar(255) not null,
    created_at timestamp not null default now()
);