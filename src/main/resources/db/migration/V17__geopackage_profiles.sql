alter table ouf_onboarding.file_profile drop constraint file_profile_format_check;
alter table ouf_onboarding.file_profile add constraint file_profile_format_check check(format in ('CSV','XLSX','GEOPACKAGE'));
