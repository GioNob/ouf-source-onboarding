alter table ouf_onboarding.file_profile drop constraint file_profile_format_check;
alter table ouf_onboarding.file_profile add constraint file_profile_format_check check(format in ('CSV','XLSX','GEOPACKAGE','ACCESS','SHAPEFILE'));
alter table ouf_onboarding.managed_file_asset drop constraint managed_file_asset_media_type_check;
alter table ouf_onboarding.managed_file_asset add constraint managed_file_asset_media_type_check check(media_type in ('text/csv','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/geopackage+sqlite3','application/x-msaccess','application/vnd.ms-access','application/zip'));
