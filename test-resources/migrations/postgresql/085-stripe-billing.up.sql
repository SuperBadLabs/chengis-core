CREATE TABLE IF NOT EXISTS invoices (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT NOT NULL,
    stripe_invoice_id   TEXT UNIQUE,
    amount_cents        INTEGER NOT NULL,
    currency            TEXT NOT NULL DEFAULT 'usd',
    status              TEXT NOT NULL DEFAULT 'draft',
    period_start        TEXT NOT NULL,
    period_end          TEXT NOT NULL,
    paid_at             TEXT,
    hosted_invoice_url  TEXT,
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_invoices_org ON invoices(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_invoices_stripe ON invoices(stripe_invoice_id);
--;;
CREATE INDEX IF NOT EXISTS idx_invoices_status ON invoices(status);
--;;
CREATE TABLE IF NOT EXISTS subscriptions (
    id                          TEXT PRIMARY KEY,
    org_id                      TEXT NOT NULL UNIQUE,
    stripe_subscription_id      TEXT UNIQUE,
    stripe_customer_id          TEXT,
    plan_id                     TEXT NOT NULL DEFAULT 'free',
    status                      TEXT NOT NULL DEFAULT 'active',
    current_period_start        TEXT,
    current_period_end          TEXT,
    cancel_at                   TEXT,
    canceled_at                 TEXT,
    created_at                  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_subscriptions_org ON subscriptions(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_subscriptions_stripe ON subscriptions(stripe_subscription_id);
--;;
CREATE INDEX IF NOT EXISTS idx_subscriptions_plan ON subscriptions(plan_id);
