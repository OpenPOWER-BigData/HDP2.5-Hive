drop table foobar_1 ;
create table foobar_1 ( dummyint int  , dummystr string ) ;
insert into table foobar_1 select count(*),'dummy 0'  from foobar_1 ;

drop table foobar_1M ;
create table foobar_1M ( dummyint bigint  , dummystr string ) ;

insert overwrite table foobar_1M
       select val_int  , concat('dummy ',val_int) from
             ( select ((((((d_1*10)+d_2)*10+d_3)*10+d_4)*10+d_5)*10+d_6) as val_int from foobar_1
                 lateral view outer explode(split("0,1,2,3,4,5,6,7,8,9",",")) tbl_1 as d_1
                 lateral view outer explode(split("0,1,2,3,4,5,6,7,8,9",",")) tbl_2 as d_2
                 lateral view outer explode(split("0,1,2,3,4,5,6,7,8,9",",")) tbl_3 as d_3
                 lateral view outer explode(split("0,1,2,3,4,5,6,7,8,9",",")) tbl_4 as d_4
                 lateral view outer explode(split("0,1,2,3,4,5,6,7,8,9",",")) tbl_5 as d_5
                 lateral view outer explode(split("0,1,2,3,4,5,6,7,8,9",",")) tbl_6 as d_6  ) as f;


set hive.optimize.sampling.orderby.number=10000;
set hive.optimize.sampling.orderby.percent=0.1f;
set mapreduce.job.reduces=3 ;

set hive.optimize.sampling.orderby=false;

select count(*) , count(distinct dummyint ) , min(dummyint),max(dummyint) from foobar_1M ;

set hive.optimize.sampling.orderby=true;

select count(*) , count(distinct dummyint ) , min(dummyint),max(dummyint) from foobar_1M ;