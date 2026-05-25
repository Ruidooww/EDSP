alter table notification_channels
    add column if not exists endpoint_secret_ciphertext text;

alter table notification_channels
    add column if not exists endpoint_secret_key_version varchar(64);

alter table notification_channels
    add column if not exists endpoint_masked text;

alter table notification_channels
    add column if not exists secret_storage_status varchar(32) not null default 'legacy_plaintext';

alter table notification_channels
    add constraint chk_notification_channels_secret_storage_status
    check (secret_storage_status in ('encrypted', 'legacy_plaintext', 'missing'));

create index if not exists idx_notification_channels_secret_storage_status
on notification_channels(secret_storage_status);
