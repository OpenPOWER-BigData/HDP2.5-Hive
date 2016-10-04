SET hive.vectorized.execution.enabled=true;
CREATE TABLE alltypesorc_part_char(ctinyint tinyint, csmallint smallint, cint int, cbigint bigint, cfloat float, cdouble double, cstring1 string, cstring2 string, ctimestamp1 timestamp, ctimestamp2 timestamp, cboolean1 boolean, cboolean2 boolean) partitioned by (ds char(4)) STORED AS ORC;
insert overwrite table alltypesorc_part_char partition (ds='2011') select * from alltypesorc limit 100;
insert overwrite table alltypesorc_part_char partition (ds='2012') select * from alltypesorc limit 100;

select count(cdouble), cint from alltypesorc_part_char where ds='2011' group by cint limit 10;
select count(*) from alltypesorc_part_char A join alltypesorc_part_char B on A.ds=B.ds;
