set hive.default.fileformat=ORC;
create table orc_test (userid bigint, string1 string, subtype double, decimal1 decimal(38,10), ts timestamp);

load data local inpath '../../data/files/kv1.txt' into table orc_test;
