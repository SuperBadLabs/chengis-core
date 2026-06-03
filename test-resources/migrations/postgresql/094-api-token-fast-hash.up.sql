-- Add token_fast_hash for O(1) API-token verification.
-- API tokens are high-entropy random strings, so a fast SHA-256 hash is
-- cryptographically sufficient — brute force is infeasible regardless of hash
-- speed. This replaces the per-request bcrypt verify (~140ms) with an indexed
-- equality lookup (microseconds). bcrypt (token_hash) is retained as a fallback
-- for legacy tokens created before this migration; those are rehashed on next use.
ALTER TABLE api_tokens ADD COLUMN token_fast_hash TEXT;

-- Partial index for fast hash-based lookup on non-revoked tokens.
CREATE INDEX IF NOT EXISTS idx_api_tokens_fast_hash ON api_tokens(token_fast_hash) WHERE revoked_at IS NULL;
