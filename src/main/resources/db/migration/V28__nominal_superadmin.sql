-- Preserve every existing role designation, transfer and history row.
-- Exactly one trusted IAM selector: organizational role OR nominal subject.
alter table ouf_authorization.superadmin_binding
 alter column role_ref drop not null,
 add column subject_id text,
 add constraint superadmin_binding_selector check (
   num_nonnulls(role_ref,subject_id)=1 and
   (subject_id is null or (length(subject_id) between 1 and 255 and subject_id ~ '^[!-~]+$'))
 );

alter table ouf_authorization.superadmin_history
 alter column role_ref drop not null,
 add column subject_id text,
 add constraint superadmin_history_selector check (
   num_nonnulls(role_ref,subject_id)=1 and
   (subject_id is null or (length(subject_id) between 1 and 255 and subject_id ~ '^[!-~]+$'))
 );

alter table ouf_authorization.superadmin_transfer
 alter column source_role drop not null,
 alter column target_role drop not null,
 add column source_subject_id text,
 add column target_subject_id text,
 add constraint superadmin_transfer_source_selector check (
   num_nonnulls(source_role,source_subject_id)=1 and
   (source_subject_id is null or (length(source_subject_id) between 1 and 255 and source_subject_id ~ '^[!-~]+$'))
 ),
 add constraint superadmin_transfer_target_selector check (
   num_nonnulls(target_role,target_subject_id)=1 and
   (target_subject_id is null or (length(target_subject_id) between 1 and 255 and target_subject_id ~ '^[!-~]+$'))
 ),
 add constraint superadmin_transfer_changes_selector check (
   source_role is distinct from target_role or source_subject_id is distinct from target_subject_id
 );

-- Existing immutable history, monotonic binding, unique pending transfer and
-- transaction/advisory-lock protections remain in place. No bootstrap reopening.
