SELECT
  p2.title,
  p2.accession_no,
  p2.zdb_id,
  p2.authors,
  p2.pub_date,
  j2.jrnl_abbrev,
  p2.pub_volume,
  p2.pub_pages
FROM (publication p1 INNER JOIN journal j1 ON p1.pub_jrnl_zdb_id = j1.jrnl_zdb_id)
  CROSS JOIN (publication p2 INNER JOIN journal j2 ON p2.pub_jrnl_zdb_id = j2.jrnl_zdb_id)
WHERE LOWER(p1.title) = LOWER(p2.title)
      AND p1.zdb_id != p2.zdb_id
      AND j1.jrnl_name != 'ZFIN Direct Data Submission'
      AND p1.jtype != 'Chapter'
      AND p1.jtype = p2.jtype
      AND (year(p1.pub_date) = year(p2.pub_date) OR (p1.pub_date IS NULL AND p2.pub_date IS NULL))
      AND p1.zdb_id NOT IN (
  'ZDB-PUB-021016-117',
  'ZDB-PUB-961014-110',
  'ZDB-PUB-961014-169',
  'ZDB-PUB-961014-170',
  'ZDB-PUB-021016-125',
  'ZDB-PUB-961014-1217',
  'ZDB-PUB-021016-60',
  'ZDB-PUB-991014-9',
  'ZDB-PUB-000125-1',
  'ZDB-PUB-990525-2',
  'ZDB-PUB-961014-288',
  'ZDB-PUB-961014-289',
  'ZDB-PUB-010718-37',
  'ZDB-PUB-010912-30',
  'ZDB-PUB-981110-12',
  'ZDB-PUB-990218-4',
  'ZDB-PUB-021016-112',
  'ZDB-PUB-961014-758',
  'ZDB-PUB-961014-759',
  'ZDB-PUB-000125-3',
  'ZDB-PUB-010131-19',
  'ZDB-PUB-021015-13',
  'ZDB-PUB-030211-13',
  'ZDB-PUB-961014-1233',
  'ZDB-PUB-961014-1234',
  'ZDB-PUB-961014-106',
  'ZDB-PUB-961014-107',
  'ZDB-PUB-010417-9',
  'ZDB-PUB-990414-35',
  'ZDB-PUB-010711-2',
  'ZDB-PUB-010814-8',
  'ZDB-PUB-000824-10',
  'ZDB-PUB-990824-40',
  'ZDB-PUB-010912-1',
  'ZDB-PUB-021017-13',
  'ZDB-PUB-980420-9',
  'ZDB-PUB-030425-13',
  'ZDB-PUB-010718-13',
  'ZDB-PUB-020913-1',
  'ZDB-PUB-990414-54',
  'ZDB-PUB-021017-3',
  'ZDB-PUB-010718-27',
  'ZDB-PUB-010821-1',
  'ZDB-PUB-021017-74',
  'ZDB-PUB-041012-5',
  'ZDB-PUB-010918-3',
  'ZDB-PUB-040216-6',
  'ZDB-PUB-111012-21',
  'ZDB-PUB-170129-6',
  'ZDB-PUB-111012-25',
  'ZDB-PUB-170129-5',
  'ZDB-PUB-141202-8',
  'ZDB-PUB-170104-10',
  'ZDB-PUB-120227-12',
  'ZDB-PUB-170214-218',
  'ZDB-PUB-050127-1',
  'ZDB-PUB-030408-12',
  'ZDB-PUB-150716-10',
  'ZDB-PUB-110523-4',
  'ZDB-PUB-160618-13',
  'ZDB-PUB-110520-28',
  'ZDB-PUB-160608-12',
  'ZDB-PUB-050128-12',
  'ZDB-PUB-160608-19',
  'ZDB-PUB-050128-4',
  'ZDB-PUB-160618-11',
  'ZDB-PUB-150711-1',
  'ZDB-PUB-160430-3',
  'ZDB-PUB-160313-4',
  'ZDB-PUB-041213-5',
  'ZDB-PUB-160219-10',
  'ZDB-PUB-110921-14',
  'ZDB-PUB-160725-24',
  'ZDB-PUB-150404-8',
  'ZDB-PUB-170825-6',
  'ZDB-PUB-170722-2',
  'ZDB-PUB-081029-1',
  'ZDB-PUB-150404-8',
  'ZDB-PUB-170825-6',
  'ZDB-PUB-080331-15',
  'ZDB-PUB-080401-1',
  'ZDB-PUB-141204-2',
  'ZDB-PUB-170214-272',
  'ZDB-PUB-120105-22',
  'ZDB-PUB-100504-2',
  'ZDB-PUB-041228-11',
  'ZDB-PUB-160429-5',
  'ZDB-PUB-160716-12',
  'ZDB-PUB-071125-8')
ORDER BY LOWER(p2.title), p2.zdb_id
