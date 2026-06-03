DROP INDEX IF EXISTS idx_api_tokens_fast_hash;
ALTER TABLE api_tokens DROP COLUMN IF EXISTS token_fast_hash;
