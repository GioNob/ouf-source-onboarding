CREATE TABLE ouf_onboarding.managed_file_chat_handoff (
  handoff_id uuid PRIMARY KEY,
  subject_id text NOT NULL,
  asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  expires_at timestamptz NOT NULL DEFAULT (transaction_timestamp() + interval '30 minutes'),
  CHECK (expires_at > created_at)
);
