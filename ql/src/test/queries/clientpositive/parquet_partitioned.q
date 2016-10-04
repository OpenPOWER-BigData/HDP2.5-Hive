set hive.exec.dynamic.partition.mode=nonstrict;
set hive.exec.dynamic.partition=true;

-- SORT_QUERY_RESULTS

DROP TABLE parquet_partitioned_staging;
DROP TABLE parquet_partitioned;

CREATE TABLE parquet_partitioned_staging (
    id int,
    str string,
    part string
) ROW FORMAT DELIMITED
FIELDS TERMINATED BY '|';

CREATE TABLE parquet_partitioned (
    id int,
    str string
) PARTITIONED BY (part string)
STORED AS PARQUET;

DESCRIBE FORMATTED parquet_partitioned;

LOAD DATA LOCAL INPATH '../../data/files/parquet_partitioned.txt' OVERWRITE INTO TABLE parquet_partitioned_staging;

SELECT * FROM parquet_partitioned_staging;

INSERT OVERWRITE TABLE parquet_partitioned PARTITION (part) SELECT * FROM parquet_partitioned_staging;

set hive.input.format=org.apache.hadoop.hive.ql.io.HiveInputFormat;
SELECT * FROM parquet_partitioned;
SELECT part, COUNT(0) FROM parquet_partitioned GROUP BY part;

set hive.input.format=org.apache.hadoop.hive.ql.io.CombineHiveInputFormat;
SELECT * FROM parquet_partitioned;
SELECT part, COUNT(0) FROM parquet_partitioned GROUP BY part;

set hive.optimize.index.filter=true;
select * from parquet_partitioned where part="part2";
select * from parquet_partitioned where part in ("part2");
select * from parquet_partitioned where part between "part0" and "part2";
select * from parquet_partitioned where part!="part2";
select * from parquet_partitioned where part<"part2";
select * from parquet_partitioned where part is null;
select * from parquet_partitioned where part is not null;
select * from parquet_partitioned where part is not null and part="part1";
select * from parquet_partitioned where part="part2" and id>=2;
select * from parquet_partitioned where part="part1" and id=1;
select * from parquet_partitioned where part="part2" and id between 2 and 3;
select * from parquet_partitioned where part="part2" and id in (2,3);
select * from parquet_partitioned where part<=>"part2" and id in (2,3);
select * from parquet_partitioned where part<>"part2" and id in (2,3);
