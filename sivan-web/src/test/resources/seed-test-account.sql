INSERT INTO accounts (account_id, username, password_hash, display_name, short_id)
VALUES ('00000000-0000-0000-0000-000000000001', 'test', 'test', 'Test', 'test-account')
ON CONFLICT (account_id) DO NOTHING;
