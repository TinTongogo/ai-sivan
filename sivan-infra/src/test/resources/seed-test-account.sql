INSERT INTO accounts (account_id, username, password_hash, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'test', 'test', 'Test')
ON CONFLICT (account_id) DO NOTHING;
