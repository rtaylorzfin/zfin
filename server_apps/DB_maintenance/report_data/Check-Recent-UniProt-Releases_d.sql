select * from uniprot_release
where now() - upr_date < '48 hours'::interval
   or upr_download_date is null;