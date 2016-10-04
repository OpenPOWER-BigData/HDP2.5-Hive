
create table inner_join_null (a int) partitioned by (num int, part2 int);
alter table inner_join_null add partition (num=201501, part2=0);
alter table inner_join_null add partition (num=201501, part2=1);
alter table inner_join_null add partition (num=201501, part2=2);
select num from inner_join_null a inner join (select max (num) as dt from inner_join_null where num<201501) b on a.num=b.dt;
