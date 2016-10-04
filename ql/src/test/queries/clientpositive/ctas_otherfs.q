
create table ctas_otherfs
  location 'pfile://${system:test.tmp.dir}/ctas_otherfs'
  as select key, value from src limit 2;

select * from ctas_otherfs;

drop table ctas_otherfs;
