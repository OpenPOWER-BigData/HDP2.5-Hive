set hive.test.mode=true;
set hive.test.mode.prefix=;
set hive.test.mode.nosamplelist=ptn_t,import_ptn_t_1,import_ptn_t_2,unptn_t,import_unptn_t_1,import_unptn_t_2;

drop table if exists ptn_t;
drop table if exists unptn_t;
drop table if exists import_ptn_t_1;
drop table if exists import_ptn_t_2;
drop table if exists import_unptn_t_1;
drop table if exists import_unptn_t_2;

dfs ${system:test.dfs.mkdir} target/tmp/ql/test/data/exports/ptn_t/temp;
dfs -rmr target/tmp/ql/test/data/exports/ptn_t;
dfs ${system:test.dfs.mkdir} target/tmp/ql/test/data/exports/unptn_t/temp;
dfs -rmr target/tmp/ql/test/data/exports/unptn_t;

dfs ${system:test.dfs.mkdir} target/tmp/ql/test/data/exports/ptn_t_1/temp;
dfs -rmr target/tmp/ql/test/data/exports/ptn_t_1;
dfs ${system:test.dfs.mkdir} target/tmp/ql/test/data/exports/ptn_t_2/temp;
dfs -rmr target/tmp/ql/test/data/exports/ptn_t_2;

dfs ${system:test.dfs.mkdir} target/tmp/ql/test/data/exports/unptn_t_1/temp;
dfs -rmr target/tmp/ql/test/data/exports/unptn_t_1;
dfs ${system:test.dfs.mkdir} target/tmp/ql/test/data/exports/unptn_t_2/temp;
dfs -rmr target/tmp/ql/test/data/exports/unptn_t_2;


create table ptn_t (emp_id int comment "employee id")
        partitioned by (emp_country string, emp_state string)
        stored as textfile;
load data local inpath "../../data/files/test.dat"
        into table ptn_t partition (emp_country="us",emp_state="ca");

create table unptn_t(emp_id int comment "employee id")
	stored as textfile;
load data local inpath "../../data/files/test.dat"
        into table unptn_t;

-- We create a table, and export the table to an export location before and after an analyze table compute statistics

describe extended ptn_t;
show table extended like 'ptn_t' partition(emp_country="us",emp_state="ca");
show create table ptn_t;

export table ptn_t to 'ql/test/data/exports/ptn_t_1' for replication('ptn_t_1');

analyze table ptn_t partition(emp_country="us",emp_state="ca") compute statistics;

describe extended ptn_t;
show table extended like 'ptn_t' partition(emp_country="us",emp_state="ca");
show create table ptn_t;

export table ptn_t to 'ql/test/data/exports/ptn_t_2' for replication('ptn_t_2');

drop table ptn_t;

-- Now the same for the unpartitioned table

describe extended unptn_t;
show create table unptn_t;

export table unptn_t to 'ql/test/data/exports/unptn_t_1' for replication('unptn_t_1');

analyze table unptn_t compute statistics;

describe extended unptn_t;
show create table unptn_t;

export table unptn_t to 'ql/test/data/exports/unptn_t_2' for replication('unptn_t_2');

drop table unptn_t;

-- Now we import

explain import table import_ptn_t_1 from 'ql/test/data/exports/ptn_t_1';
import table import_ptn_t_1 from 'ql/test/data/exports/ptn_t_1';
describe extended import_ptn_t_1;
show table extended like 'import_ptn_t_1' partition(emp_country="us",emp_state="ca");
show create table import_ptn_t_1;

explain import table import_ptn_t_2 from 'ql/test/data/exports/ptn_t_2';
import table import_ptn_t_2 from 'ql/test/data/exports/ptn_t_2';
describe extended import_ptn_t_2;
show table extended like 'import_ptn_t_2' partition(emp_country="us",emp_state="ca");
show create table import_ptn_t_2;

import table import_unptn_t_1 from 'ql/test/data/exports/unptn_t_1';
describe extended import_unptn_t_1;
show table extended like 'import_unptn_t_1';
show create table import_unptn_t_1;

import table import_unptn_t_2 from 'ql/test/data/exports/unptn_t_2';
describe extended import_unptn_t_2;
show table extended like 'import_unptn_t_2';
show create table import_unptn_t_2;

-- clean up

drop table import_ptn_t_1;
drop table import_ptn_t_2;
drop table import_unptn_t_1;
drop table import_unptn_t_2;

