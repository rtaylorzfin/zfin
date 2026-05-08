--P_CHECK_ZDB_OBJECT_TABLE
  ------------------------------------------------------
 --procedure that checks to make sure tables and columns in 
  --zdb_object_type table exist in pg_tables and pg_attributes.
  --REPLACES:
  --sub zdbObjectHomeTableColumnExist 

  create or replace function p_check_zdb_object_table (vTableName text,
	  				     vColumnName text)
  returns void as $$
  declare vOkInSystables		integer;
   vOkInSyscolumns	integer;
   vTableId		integer;
   -- vTableName may be schema-qualified ('schema.table') or bare ('table').
   -- Bare names default to the public schema.
   vSchema		text := 'public';
   vBareTable		text := vTableName;
  begin
   if position('.' in vTableName) > 0 then
       vSchema    := split_part(vTableName, '.', 1);
       vBareTable := split_part(vTableName, '.', 2);
   end if;

   vTableId = (select c.oid
		   from pg_class c
		   join pg_namespace n on n.oid = c.relnamespace
		   where n.nspname = vSchema and c.relname = vBareTable);

   vOkInSystables = (select count(*)
			  from pg_tables
			  where schemaname = vSchema and tablename = vBareTable);
  raise notice 'vOkInSystables: %', vOkInSystables;
  raise notice 'vTableid: %', vTableid;

  if vOkInSystables < 1 then
    raise exception 'FAIL!: table name not in systables';
  else 
	 vOkInSyscolumns = (select count(*) 
	 			 from pg_attribute 
  				 where attrelid = vTableid
				 and attname = vColumnName);
	raise notice 'vColumnName: %', vColumnName;

	if vOkInSyscolumns < 1 then
	  raise exception 'FAIL!: column name not in syscolumns';
	end if;

  end if;
 end
$$ LANGUAGE plpgsql
